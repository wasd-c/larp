package com.anis.larp.ui.freemode

import android.content.Context
import android.util.Log
import com.anis.larp.model.AccelerationKind
import com.anis.larp.model.DeviceAccelerationProfile
import com.anis.larp.model.OpenPromptModel
import com.anis.larp.model.PromptModelSource
import com.anis.larp.model.PromptModelRecord
import com.anis.larp.learning.LearningContentRepository
import com.anis.larp.learning.LearningContentAction
import com.anis.larp.learning.LearningContentToolSet
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.tool
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalApi::class)
class LiteRtReplyGenerator(context: Context) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val modelSource = PromptModelSource(applicationContext)
    private var activeEngine: Engine? = null
    private var activeModelKey: String? = null
    private var activeModel: OpenPromptModel? = null
    private var activeBackendLabel: String? = null

    suspend fun preload(
        record: PromptModelRecord,
        onPreparingModel: (String) -> Unit
    ): String = withContext(Dispatchers.Default) {
        getOrInitializeEngine(record, onPreparingModel)
        runtimeDescription(record)
    }

    internal suspend fun generateReply(
        record: PromptModelRecord,
        transcript: String,
        recognitionLocale: Locale,
        tutorContext: TutorContext,
        conversationHistory: List<ConversationTurn> = emptyList(),
        requestedContentKind: LearningContentRequestKind? = null,
        onPreparingModel: (String) -> Unit,
        onContentActionExecuted: (LearningContentAction) -> Unit = {}
    ): GeneratedReply = withContext(Dispatchers.Default) {
        val engine = getOrInitializeEngine(record, onPreparingModel)
        val modelLabel = knownModelLabel(record.displayName, record.repository)
        val generatedReply = if (requestedContentKind != null) {
            generateRequestedLearningContent(
                engine = engine,
                kind = requestedContentKind,
                transcript = transcript,
                recognitionLocale = recognitionLocale,
                tutorContext = tutorContext,
                conversationHistory = conversationHistory,
                modelLabel = modelLabel,
                onContentActionExecuted = onContentActionExecuted
            )
        } else {
            var nativeActionExecuted = false
            val toolProvider = tool(
                LearningContentToolSet(
                    LearningContentRepository.getInstance(applicationContext),
                    onActionExecuted = { action ->
                        nativeActionExecuted = true
                        onContentActionExecuted(action)
                    }
                )
            )
            val rawReply = generateRawReply(
                engine = engine,
                prompt = transcript,
                config = ConversationConfig(
                    systemInstruction = Contents.of(
                        tutorSystemInstruction(
                            recognitionLocale = recognitionLocale,
                            tutorContext = tutorContext,
                            toolMode = TutorToolMode.NATIVE
                        )
                    ),
                    initialMessages = conversationHistory.flatMap { turn ->
                        listOf(
                            Message.user(turn.userMessage),
                            Message.model(turn.assistantMessage)
                        )
                    },
                    tools = listOf(toolProvider),
                    automaticToolCalling = true
                )
            )
            parseGeneratedReply(
                rawReply = rawReply,
                fallbackLocale = tutorContext.targetLanguage,
                contentLanguageTag = tutorContext.targetLanguage.toLanguageTag()
            ).copy(contentActionAlreadyExecuted = nativeActionExecuted)
        }
        generatedReply.copy(
            modelName = modelLabel,
            acceleration = runtimeDescription(record)
        )
    }

    /**
     * Gemma's native LiteRT tool calling is more reliable than asking the small
     * model to reproduce a tagged text protocol. Try the actual save tool first.
     * The tagged generator remains a fallback for models which do not call tools.
     */
    private suspend fun generateRequestedLearningContent(
        engine: Engine,
        kind: LearningContentRequestKind,
        transcript: String,
        recognitionLocale: Locale,
        tutorContext: TutorContext,
        conversationHistory: List<ConversationTurn>,
        modelLabel: String,
        onContentActionExecuted: (LearningContentAction) -> Unit
    ): GeneratedReply {
        var executedAction: LearningContentAction? = null
        val toolProvider = tool(
            LearningContentToolSet(
                LearningContentRepository.getInstance(applicationContext),
                onActionExecuted = { action ->
                    executedAction = action
                    onContentActionExecuted(action)
                }
            )
        )
        val nativeReply = runCatching {
            generateRawReply(
                engine = engine,
                prompt = transcript,
                config = ConversationConfig(
                    systemInstruction = Contents.of(
                        tutorSystemInstruction(
                            recognitionLocale = recognitionLocale,
                            tutorContext = tutorContext,
                            toolMode = TutorToolMode.NATIVE
                        )
                    ),
                    initialMessages = conversationHistory.flatMap { turn ->
                        listOf(
                            Message.user(turn.userMessage),
                            Message.model(turn.assistantMessage)
                        )
                    },
                    tools = listOf(toolProvider),
                    automaticToolCalling = true
                )
            )
        }

        val savedAction = executedAction
        if (savedAction != null && kind.matches(savedAction)) {
            val parsed = nativeReply.getOrNull()?.let { rawReply ->
                runCatching {
                    parseGeneratedReply(
                        rawReply = rawReply,
                        fallbackLocale = tutorContext.nativeLanguage,
                        contentLanguageTag = tutorContext.targetLanguage.toLanguageTag()
                    )
                }.getOrNull()
            } ?: GeneratedReply(
                text = creationConfirmation(kind, tutorContext.nativeLanguage),
                locale = tutorContext.nativeLanguage
            )
            return parsed.copy(
                contentAction = savedAction,
                contentActionAlreadyExecuted = true
            )
        }

        return generateVerifiedLearningContentReply(
            kind = kind,
            transcript = transcript,
            tutorContext = tutorContext,
            conversationHistory = conversationHistory,
            modelLabel = modelLabel
        ) { prompt ->
            generateRawReply(
                engine = engine,
                prompt = prompt,
                config = ConversationConfig()
            )
        }
    }

    private suspend fun generateRawReply(
        engine: Engine,
        prompt: String,
        config: ConversationConfig
    ): String {
        val rawReply = withTimeout(90_000) {
            engine.createConversation(config).use { conversation ->
                conversation.sendMessage(prompt)
                    .contents
                    .contents
                    .filterIsInstance<Content.Text>()
                    .joinToString(separator = "") { it.text }
                    .trim()
            }
        }
        return rawReply.ifBlank {
            throw IllegalStateException("Le modèle n'a produit aucune réponse.")
        }
    }

    @Synchronized
    private fun getOrInitializeEngine(
        record: PromptModelRecord,
        onPreparingModel: (String) -> Unit
    ): Engine {
        val requestedModelKey = record.contentUri
            ?.takeIf(String::isNotBlank)
            ?: record.filePath
        if (
            activeModelKey == requestedModelKey &&
            activeEngine?.isInitialized() == true
        ) {
            return requireNotNull(activeEngine)
        }

        closeEngine()
        val accelerationProfile = DeviceAccelerationProfile.detect()
        require(accelerationProfile.supportsArtifact(record.artifactFileName)) {
            "L'artefact ${record.artifactFileName ?: record.displayName} a été compilé " +
                "pour un autre SoC et ne peut pas être initialisé sans risque sur " +
                "${accelerationProfile.label}."
        }
        onPreparingModel(
            "Préparation de ${knownModelLabel(record.displayName, record.repository)} " +
                "depuis le modèle partagé…"
        )
        val openedModel = modelSource.open(record)
        onPreparingModel(
            "Chargement de ${knownModelLabel(record.displayName, record.repository)} " +
                "sur l'accélérateur…"
        )
        ExperimentalFlags.enableSpeculativeDecoding =
            record.speculativeDecoding

        try {
            val errors = mutableListOf<Throwable>()
            if (accelerationProfile.hasNpu) {
                Log.i(
                    TAG,
                    "NPU détecté: ${accelerationProfile.label}; " +
                        "runtime=${accelerationProfile.npuRuntimeLabel}; " +
                        "modèle=${record.displayName}; hint=${record.accelerationHint}"
                )
            }
            var npuFailure: Throwable? = null
            for (candidate in backendCandidates(
                hint = record.accelerationHint,
                profile = accelerationProfile
            )) {
                if (candidate.kind == AccelerationKind.NPU) {
                    logNpuAttempt(candidate, record)
                } else if (
                    candidate.kind == AccelerationKind.GPU &&
                    npuFailure != null
                ) {
                    Log.w(
                        TAG,
                        "Fallback GPU OpenCL après échec NPU: " +
                            npuFailure.exactFailureMessage()
                    )
                } else {
                    Log.i(TAG, "Tentative backend ${candidate.label}")
                }
                val engine = Engine(
                    EngineConfig(
                        modelPath = openedModel.path,
                        backend = candidate.backend,
                        maxNumTokens = candidate.maxTokens,
                        cacheDir = applicationContext.cacheDir.absolutePath
                    )
                )
                try {
                    engine.initialize()
                    activeEngine = engine
                    activeModelKey = openedModel.key
                    activeModel = openedModel
                    activeBackendLabel = candidate.label
                    if (candidate.kind == AccelerationKind.NPU) {
                        Log.i(
                            TAG,
                            "Succès QNN/HTP: backend NPU initialisé; " +
                                "runtime=${accelerationProfile.npuRuntimeLabel}; " +
                                "contexte=${candidate.maxTokens}"
                        )
                    } else {
                        Log.i(
                            TAG,
                            "Backend ${candidate.label} initialisé avec succès; " +
                                "contexte=${candidate.maxTokens}"
                        )
                    }
                    return engine
                } catch (error: Throwable) {
                    if (candidate.kind == AccelerationKind.NPU) {
                        npuFailure = error
                        Log.e(
                            TAG,
                            "Échec QNN/HTP exact: ${error.exactFailureMessage()}",
                            error
                        )
                    } else {
                        Log.e(
                            TAG,
                            "Échec backend ${candidate.label}: " +
                                error.exactFailureMessage(),
                            error
                        )
                    }
                    errors += IllegalStateException(
                        "${candidate.label} : ${error.exactFailureMessage()}",
                        error
                    )
                    closeInitializedEngine(engine)
                }
            }
            throw IllegalStateException(
                "Aucun accélérateur compatible n'a pu charger ${record.displayName}. " +
                    (errors.lastOrNull()?.message
                        ?: "Vérifiez le modèle sélectionné.")
            )
        } catch (error: Throwable) {
            openedModel.close()
            throw error
        }
    }

    private fun backendCandidates(
        hint: AccelerationKind,
        profile: DeviceAccelerationProfile
    ): List<BackendCandidate> = when {
        hint == AccelerationKind.CPU -> listOf(cpuCandidate())

        hint == AccelerationKind.GPU -> listOf(gpuCandidate(), cpuCandidate())

        hint == AccelerationKind.NPU ||
            (hint == AccelerationKind.AUTO && profile.hasNpu) -> listOf(
            BackendCandidate(
                backend = Backend.NPU(
                    applicationContext.applicationInfo.nativeLibraryDir
                ),
                label = profile.npuRuntimeLabel ?: "NPU",
                kind = AccelerationKind.NPU,
                maxTokens = LITERT_NPU_CONTEXT_TOKENS
            ),
            gpuCandidate(),
            cpuCandidate()
        )

        else -> listOf(gpuCandidate(), cpuCandidate())
    }

    private fun gpuCandidate() = BackendCandidate(
        backend = Backend.GPU(),
        label = "GPU OpenCL",
        kind = AccelerationKind.GPU,
        maxTokens = LITERT_TOTAL_CONTEXT_TOKENS
    )

    private fun cpuCandidate() = BackendCandidate(
        backend = Backend.CPU(),
        label = "CPU",
        kind = AccelerationKind.CPU,
        maxTokens = LITERT_TOTAL_CONTEXT_TOKENS
    )

    private fun logNpuAttempt(
        candidate: BackendCandidate,
        record: PromptModelRecord
    ) {
        val nativeLibraryDir = applicationContext.applicationInfo.nativeLibraryDir
        val bundledLibraries = java.io.File(nativeLibraryDir)
            .listFiles()
            .orEmpty()
            .map(java.io.File::getName)
        val dispatchLibraries = bundledLibraries.filter { name ->
            name.contains("dispatch", ignoreCase = true) ||
                name.contains("qnn", ignoreCase = true)
        }
        Log.i(
            TAG,
            "Tentative QNN: backend=${candidate.label}; " +
                "SoC=${android.os.Build.SOC_MODEL}; dispatchDir=$nativeLibraryDir; " +
                "bibliothèques=${dispatchLibraries.ifEmpty { listOf("aucun dispatcher QNN embarqué") }}; " +
                "modèle=${record.displayName}; contexte=${candidate.maxTokens}"
        )
    }

    private fun Throwable.exactFailureMessage(): String =
        generateSequence(this) { it.cause }
            .take(5)
            .joinToString(" <- ") { cause ->
                "${cause::class.java.simpleName}: " +
                    (cause.message ?: "sans message")
            }

    private fun runtimeDescription(record: PromptModelRecord): String = buildString {
        append(activeBackendLabel ?: "CPU")
        if (record.speculativeDecoding) {
            append(" · décodage spéculatif MTP")
        }
    }

    @Synchronized
    private fun closeEngine() {
        activeEngine?.let(::closeInitializedEngine)
        activeEngine = null
        activeModel?.close()
        activeModel = null
        activeModelKey = null
        activeBackendLabel = null
    }

    private fun closeInitializedEngine(engine: Engine) {
        if (engine.isInitialized()) {
            runCatching(engine::close)
        }
    }

    override fun close() {
        closeEngine()
    }

}

internal const val LITERT_TOTAL_CONTEXT_TOKENS = 8_192
internal const val LITERT_NPU_CONTEXT_TOKENS = 4_096

private data class BackendCandidate(
    val backend: Backend,
    val label: String,
    val kind: AccelerationKind,
    val maxTokens: Int
)

private const val TAG = "LarpLiteRt"
