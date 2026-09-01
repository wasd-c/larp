package com.anis.larp.ui.freemode

import android.content.Context
import android.util.Log
import com.anis.larp.learning.Exercise
import com.anis.larp.learning.LearningContentRepository
import com.anis.larp.learning.LearningContentAction
import com.anis.larp.learning.Lesson
import com.anis.larp.learning.YoutubeTranscriptSource
import com.anis.larp.model.ModelPreferences
import com.anis.larp.model.LearningLanguage
import com.anis.larp.model.DeviceAccelerationProfile
import com.anis.larp.model.PromptModelRecord
import com.anis.larp.model.PromptModelCatalog
import com.anis.larp.model.requestsLearningLanguageSwitch
import com.anis.larp.model.promptModelSupportsTargetLanguage
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PromptReplyRouter(
    context: Context,
    private val preferences: ModelPreferences,
    private val catalog: PromptModelCatalog,
    private val onContentActionExecuted: (LearningContentAction) -> Unit = {}
) : AutoCloseable {
    private val geminiNano = GeminiNanoReplyGenerator()
    private val liteRt = LiteRtReplyGenerator(context)
    private val learningContentRepository =
        LearningContentRepository.getInstance(context)
    private val conversationHistory = ArrayDeque<ConversationTurn>()
    private val modelMutex = Mutex()
    private val selectionLock = Any()
    @Volatile private var cachedSelectionId: String? = null
    @Volatile private var cachedSelection: PromptModelRecord? = null

    suspend fun preloadSelectedModel(
        onPreparingModel: (String) -> Unit
    ): String? = modelMutex.withLock {
        requirePromptModelSupports(preferences.targetLanguage)
        if (preferences.promptModelId == ModelPreferences.PROMPT_GEMINI_NANO) {
            liteRt.close()
            geminiNano.prepare {
                onPreparingModel("Téléchargement de Gemini Nano sur l'appareil…")
            }
            return "Gemini prêt via Android AI Core"
        }

        geminiNano.release()
        val record = selectedCompatibleLiteRtRecord() ?: return null
        val runtime = liteRt.preload(record, onPreparingModel)
        return "${knownModelLabel(record.displayName, record.repository)} prêt · $runtime"
    }

    suspend fun generateReply(
        transcript: String,
        recognitionLocale: Locale,
        onPreparingModel: (String) -> Unit
    ): GeneratedReply = modelMutex.withLock {
        val storedHistory = conversationHistory.toList()
        val requestedContentKind = requestedLearningContentKind(
            transcript = transcript,
            conversationHistory = storedHistory
        )
        val explicitlyNamedLanguage = LearningLanguage.explicitlyNamedIn(transcript)
        if (
            requestedContentKind == null &&
            explicitlyNamedLanguage != null &&
            requestsLearningLanguageSwitch(transcript)
        ) {
            preferences.targetLanguage = explicitlyNamedLanguage
        }
        val tutorContext = currentTutorContext(
            targetLanguage = if (requestedContentKind != null) {
                explicitlyNamedLanguage?.locale ?: preferences.targetLanguage.locale
            } else {
                preferences.targetLanguage.locale
            }
        )
        val modelHistory = compactConversationHistory(storedHistory)
        val generatedReply = generateWithSelectedModel(
            transcript = transcript,
            recognitionLocale = recognitionLocale,
            tutorContext = tutorContext,
            history = modelHistory,
            requestedContentKind = requestedContentKind,
            onPreparingModel = onPreparingModel,
            onNativeContentAction = onContentActionExecuted
        )
        if (!generatedReply.contentActionAlreadyExecuted) {
            generatedReply.contentAction?.let { action ->
                learningContentRepository.execute(action)
                onContentActionExecuted(action)
            }
        }
        val contentWasSaved = generatedReply.contentActionAlreadyExecuted ||
            generatedReply.contentAction != null
        if (requestedContentKind != null && !contentWasSaved) {
            throw IllegalStateException(
                "${generatedReply.modelName.ifBlank(::selectedModelLabel)} n'a pas créé " +
                    "le contenu demandé. Rien n'a été enregistré."
            )
        }
        if (!contentWasSaved && claimsUnverifiedContentCreation(generatedReply.text)) {
            throw IllegalStateException(
                "${generatedReply.modelName.ifBlank(::selectedModelLabel)} a annoncé une " +
                    "création sans exécuter l'action locale. Rien n'a été enregistré."
            )
        }
        conversationHistory.addLast(
            ConversationTurn(
                userMessage = transcript,
                assistantMessage = generatedReply.text
            )
        )
        while (conversationHistory.size > MAX_STORED_HISTORY_TURNS) {
            conversationHistory.removeFirst()
        }
        generatedReply
    }

    suspend fun remixExercise(
        exercise: Exercise,
        guidance: String,
        onPreparingModel: (String) -> Unit = {}
    ) = modelMutex.withLock {
        val tutorContext = currentTutorContext(
            targetLanguage = LearningLanguage.explicitlyNamedIn(guidance)?.locale
                ?: Locale.forLanguageTag(exercise.languageTag)
        )
        val generatedReply = generateWithSelectedModel(
            transcript = exerciseRemixRequest(exercise, guidance),
            recognitionLocale = tutorContext.nativeLanguage,
            tutorContext = tutorContext,
            history = emptyList(),
            requestedContentKind = LearningContentRequestKind.EXERCISE,
            onPreparingModel = onPreparingModel
        )
        val action = generatedReply.contentAction as?
            LearningContentAction.CreateLessonContent
            ?: throw IllegalStateException(
                "${generatedReply.modelName.ifBlank(::selectedModelLabel)} n'a pas produit " +
                    "un exercice remixé complet. Rien n'a été enregistré."
            )
        learningContentRepository.execute(action)
    }

    suspend fun remixLesson(
        lesson: Lesson,
        guidance: String,
        onPreparingModel: (String) -> Unit = {}
    ) = modelMutex.withLock {
        val tutorContext = currentTutorContext(
            targetLanguage = LearningLanguage.explicitlyNamedIn(guidance)?.locale
                ?: Locale.forLanguageTag(lesson.languageTag)
        )
        val generatedReply = generateWithSelectedModel(
            transcript = lessonRemixRequest(lesson, guidance),
            recognitionLocale = tutorContext.nativeLanguage,
            tutorContext = tutorContext,
            history = emptyList(),
            requestedContentKind = LearningContentRequestKind.LESSON,
            onPreparingModel = onPreparingModel
        )
        val action = generatedReply.contentAction as?
            LearningContentAction.CreateLessonContent
            ?: throw IllegalStateException(
                "${generatedReply.modelName.ifBlank(::selectedModelLabel)} n'a pas produit " +
                    "une leçon remixée complète. Rien n'a été enregistré."
            )
        learningContentRepository.execute(action)
    }

    suspend fun importExerciseFromText(
        sourceText: String,
        onPreparingModel: (String) -> Unit = {}
    ) = modelMutex.withLock {
        createImportedExercise(
            request = textImportExerciseRequest(sourceText),
            onPreparingModel = onPreparingModel
        )
    }

    suspend fun importExerciseFromYoutube(
        transcript: YoutubeTranscriptSource,
        onPreparingModel: (String) -> Unit = {}
    ) = modelMutex.withLock {
        createImportedExercise(
            request = youtubeImportExerciseRequest(
                transcript = transcript,
                tutorContext = currentTutorContext()
            ),
            onPreparingModel = onPreparingModel
        )
    }

    suspend fun answerLessonQuestion(
        lesson: Lesson,
        question: String,
        conversationHistory: List<ConversationTurn>,
        onPreparingModel: (String) -> Unit = {}
    ): GeneratedReply = modelMutex.withLock {
        val cleanQuestion = question.trim().take(MAX_LESSON_QUESTION_CHARACTERS)
        require(cleanQuestion.isNotBlank()) { "Écrivez une question sur la leçon." }
        val lessonLocale = Locale.forLanguageTag(lesson.languageTag)
            .takeIf { it.language.isNotBlank() }
            ?: preferences.targetLanguage.locale
        val tutorContext = currentTutorContext(targetLanguage = lessonLocale)
        generateWithSelectedModel(
            transcript = cleanQuestion,
            recognitionLocale = tutorContext.nativeLanguage,
            tutorContext = tutorContext,
            history = compactConversationHistory(conversationHistory),
            requestedContentKind = null,
            lessonContext = LessonChatContext(
                id = lesson.id,
                title = lesson.title.take(MAX_LESSON_CONTEXT_FIELD_CHARACTERS),
                objective = lesson.objective.take(MAX_LESSON_CONTEXT_FIELD_CHARACTERS),
                content = lesson.content.take(MAX_LESSON_CONTEXT_CHARACTERS),
                topic = lesson.topic.take(MAX_LESSON_CONTEXT_FIELD_CHARACTERS)
            ),
            onPreparingModel = onPreparingModel
        ).copy(contentAction = null, contentActionAlreadyExecuted = false)
    }

    private suspend fun createImportedExercise(
        request: String,
        onPreparingModel: (String) -> Unit
    ) {
        val tutorContext = currentTutorContext()
        val generatedReply = generateWithSelectedModel(
            transcript = request,
            recognitionLocale = tutorContext.nativeLanguage,
            tutorContext = tutorContext,
            history = emptyList(),
            requestedContentKind = LearningContentRequestKind.EXERCISE,
            onPreparingModel = onPreparingModel
        )
        val action = generatedReply.contentAction as?
            LearningContentAction.CreateLessonContent
            ?: throw IllegalStateException(
                "${generatedReply.modelName.ifBlank(::selectedModelLabel)} n'a pas produit " +
                    "un quiz complet. Rien n'a été enregistré."
            )
        learningContentRepository.execute(action)
    }

    private suspend fun generateWithSelectedModel(
        transcript: String,
        recognitionLocale: Locale,
        tutorContext: TutorContext,
        history: List<ConversationTurn>,
        requestedContentKind: LearningContentRequestKind?,
        lessonContext: LessonChatContext? = null,
        onPreparingModel: (String) -> Unit,
        onNativeContentAction: (LearningContentAction) -> Unit = {}
    ): GeneratedReply {
        requirePromptModelSupports(
            LearningLanguage.fromLanguageTag(tutorContext.targetLanguage.toLanguageTag())
        )
        return if (
            preferences.promptModelId == ModelPreferences.PROMPT_GEMINI_NANO
        ) {
            liteRt.close()
            geminiNano.generateReply(
                transcript = transcript,
                recognitionLocale = recognitionLocale,
                tutorContext = tutorContext,
                conversationHistory = history,
                requestedContentKind = requestedContentKind,
                lessonContext = lessonContext,
                onPreparingModel = {
                    onPreparingModel(
                        "Téléchargement de Gemini Nano sur l'appareil…"
                    )
                }
            )
        } else {
            geminiNano.release()
            val record = selectedCompatibleLiteRtRecord()
                ?: throw IllegalStateException(
                    "${selectedModelLabel()} est encore en téléchargement ou n'est plus disponible. " +
                        "Vérifiez Modèles dans les paramètres."
                )
            liteRt.generateReply(
                record = record,
                transcript = transcript,
                recognitionLocale = recognitionLocale,
                tutorContext = tutorContext,
                conversationHistory = history,
                requestedContentKind = requestedContentKind,
                lessonContext = lessonContext,
                onPreparingModel = onPreparingModel,
                onContentActionExecuted = onNativeContentAction
            )
        }
    }

    private fun requirePromptModelSupports(targetLanguage: LearningLanguage) {
        check(promptModelSupportsTargetLanguage(preferences.promptModelId, targetLanguage)) {
            "Gemini Nano n'est pas validé pour le chinois. Choisissez Gemma 4 dans les réglages."
        }
    }

    private suspend fun selectedCompatibleLiteRtRecord(): PromptModelRecord? {
        val requestedId = preferences.promptModelId
        cachedSelection?.takeIf { cachedSelectionId == requestedId }?.let { return it }
        return withContext(Dispatchers.IO) {
            synchronized(selectionLock) {
                cachedSelection?.takeIf { cachedSelectionId == requestedId }
                    ?: resolveCompatibleLiteRtRecord(requestedId)?.also { resolved ->
                        cachedSelectionId = resolved.id
                        cachedSelection = resolved
                    }
            }
        }
    }

    private fun resolveCompatibleLiteRtRecord(requestedId: String): PromptModelRecord? {
        val models = catalog.availableModels()
        val selected = models.firstOrNull { it.id == requestedId } ?: return null
        val profile = DeviceAccelerationProfile.detect()
        if (profile.supportsArtifact(selected.artifactFileName)) return selected

        val fallback = compatibleArtifactFallback(models, selected, profile)
        Log.e(
            TAG,
            "Artefact ${selected.artifactFileName} incompatible avec ${profile.label}; " +
                if (fallback != null) {
                    "fallback sûr vers ${fallback.artifactFileName}"
                } else {
                    "aucun fallback compatible disponible"
                }
        )
        if (fallback != null) preferences.promptModelId = fallback.id
        return fallback
    }

    private fun currentTutorContext(
        targetLanguage: Locale = preferences.targetLanguage.locale
    ) = TutorContext(
        nativeLanguage = Locale.forLanguageTag(preferences.nativeLanguageTag),
        targetLanguage = targetLanguage
    )

    fun selectedModelLabel(): String {
        if (preferences.promptModelId == ModelPreferences.PROMPT_GEMINI_NANO) {
            return "Gemini"
        }
        val record = cachedSelection.takeIf {
            cachedSelectionId == preferences.promptModelId
        }
        return knownModelLabel(
            record?.displayName,
            record?.repository ?: preferences.promptModelId
        )
    }

    fun endConversation() {
        conversationHistory.clear()
        liteRt.endConversation()
    }

    override fun close() {
        geminiNano.release()
        liteRt.close()
    }

    private companion object {
        const val TAG = "LarpLiteRt"
        const val MAX_LESSON_QUESTION_CHARACTERS = 1_000
        const val MAX_LESSON_CONTEXT_CHARACTERS = 5_000
        const val MAX_LESSON_CONTEXT_FIELD_CHARACTERS = 300
    }
}

internal fun compatibleArtifactFallback(
    models: List<PromptModelRecord>,
    selected: PromptModelRecord,
    profile: DeviceAccelerationProfile
): PromptModelRecord? = models.firstOrNull { candidate ->
    val sameKnownModel = candidate.repository.equals(
        selected.repository,
        ignoreCase = true
    ) || (
        selected.displayName.contains("gemma", ignoreCase = true) &&
            candidate.displayName.contains("gemma", ignoreCase = true)
        )
    sameKnownModel && candidate.artifactFileName.equals(
        profile.gemmaArtifactFileName,
        ignoreCase = true
    ) && profile.supportsArtifact(candidate.artifactFileName)
}
