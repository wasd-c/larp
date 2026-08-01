package com.anis.larp.ui.freemode

import android.content.Context
import android.util.Log
import com.anis.larp.learning.Exercise
import com.anis.larp.learning.LearningContentRepository
import com.anis.larp.learning.LearningContentAction
import com.anis.larp.learning.Lesson
import com.anis.larp.learning.APPROVED_TOPIC_TAGS_PROMPT
import com.anis.larp.learning.YoutubeTranscriptSource
import com.anis.larp.model.ModelPreferences
import com.anis.larp.model.DeviceAccelerationProfile
import com.anis.larp.model.PromptModelRecord
import com.anis.larp.model.PromptModelCatalog
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    suspend fun preloadSelectedModel(
        onPreparingModel: (String) -> Unit
    ): String? = modelMutex.withLock {
        if (preferences.promptModelId == ModelPreferences.PROMPT_GEMINI_NANO) {
            liteRt.close()
            geminiNano.prepare {
                onPreparingModel("Téléchargement de Gemini Nano sur l'appareil…")
            }
            return "Gemini prêt via Android AI Core"
        }

        val record = selectedCompatibleLiteRtRecord() ?: return null
        val runtime = liteRt.preload(record, onPreparingModel)
        return "${knownModelLabel(record.displayName, record.repository)} prêt · $runtime"
    }

    suspend fun generateReply(
        transcript: String,
        recognitionLocale: Locale,
        onPreparingModel: (String) -> Unit
    ): GeneratedReply = modelMutex.withLock {
        val tutorContext = currentTutorContext()
        val history = conversationHistory.toList()
        val requestedContentKind = requestedLearningContentKind(
            transcript = transcript,
            conversationHistory = history
        )
        val generatedReply = generateWithSelectedModel(
            transcript = transcript,
            recognitionLocale = recognitionLocale,
            tutorContext = tutorContext,
            history = history,
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
        while (conversationHistory.size > MAX_HISTORY_TURNS) {
            conversationHistory.removeFirst()
        }
        generatedReply
    }

    suspend fun remixExercise(
        exercise: Exercise,
        guidance: String,
        onPreparingModel: (String) -> Unit = {}
    ) = modelMutex.withLock {
        val tutorContext = currentTutorContext()
        val generatedReply = generateWithSelectedModel(
            transcript = exerciseRemixRequest(exercise, guidance),
            recognitionLocale = tutorContext.nativeLanguage,
            tutorContext = tutorContext,
            history = emptyList(),
            requestedContentKind = LearningContentRequestKind.EXERCISE,
            onPreparingModel = onPreparingModel
        )
        val action = generatedReply.contentAction as?
            LearningContentAction.CreateExercise
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
        val tutorContext = currentTutorContext()
        val generatedReply = generateWithSelectedModel(
            transcript = lessonRemixRequest(lesson, guidance),
            recognitionLocale = tutorContext.nativeLanguage,
            tutorContext = tutorContext,
            history = emptyList(),
            requestedContentKind = LearningContentRequestKind.LESSON,
            onPreparingModel = onPreparingModel
        )
        val action = generatedReply.contentAction as?
            LearningContentAction.CreateLesson
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
            LearningContentAction.CreateExercise
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
        onPreparingModel: (String) -> Unit,
        onNativeContentAction: (LearningContentAction) -> Unit = {}
    ): GeneratedReply = if (
            preferences.promptModelId == ModelPreferences.PROMPT_GEMINI_NANO
        ) {
            liteRt.close()
            geminiNano.generateReply(
                transcript = transcript,
                recognitionLocale = recognitionLocale,
                tutorContext = tutorContext,
                conversationHistory = history,
                requestedContentKind = requestedContentKind,
                onPreparingModel = {
                    onPreparingModel(
                        "Téléchargement de Gemini Nano sur l'appareil…"
                    )
                }
            )
        } else {
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
                onPreparingModel = onPreparingModel,
                onContentActionExecuted = onNativeContentAction
            )
        }

    private fun selectedCompatibleLiteRtRecord(): PromptModelRecord? {
        val models = catalog.availableModels()
        val selected = models.firstOrNull { it.id == preferences.promptModelId }
            ?: return null
        val profile = DeviceAccelerationProfile.detect()
        if (profile.supportsArtifact(selected.artifactFileName)) return selected

        val fallback = models.firstOrNull { candidate ->
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
                ) &&
                profile.supportsArtifact(candidate.artifactFileName)
        }
        Log.e(
            TAG,
            "Artefact ${selected.artifactFileName} incompatible avec ${profile.label}; " +
                if (fallback != null) {
                    "fallback sûr vers ${fallback.artifactFileName}"
                } else {
                    "aucun fallback compatible disponible"
                }
        )
        if (fallback != null) {
            preferences.promptModelId = fallback.id
        }
        return fallback
    }

    private fun currentTutorContext() = TutorContext(
        nativeLanguage = Locale.forLanguageTag(preferences.nativeLanguageTag),
        targetLanguage = preferences.targetLanguage.locale
    )

    fun selectedModelLabel(): String {
        if (preferences.promptModelId == ModelPreferences.PROMPT_GEMINI_NANO) {
            return "Gemini"
        }
        val record = catalog.find(preferences.promptModelId)
        return knownModelLabel(
            record?.displayName,
            record?.repository ?: preferences.promptModelId
        )
    }

    fun endConversation() {
        conversationHistory.clear()
    }

    override fun close() {
        geminiNano.close()
        liteRt.close()
    }

    private companion object {
        const val TAG = "LarpLiteRt"
    }
}

data class TutorContext(
    val nativeLanguage: Locale,
    val targetLanguage: Locale
)

data class ConversationTurn(
    val userMessage: String,
    val assistantMessage: String
)

enum class TutorToolMode {
    NONE,
    NATIVE,
    TAGGED_ACTIONS
}

internal fun tutorPrompt(
    transcript: String,
    recognitionLocale: Locale,
    tutorContext: TutorContext,
    conversationHistory: List<ConversationTurn> = emptyList(),
    toolMode: TutorToolMode = TutorToolMode.NONE
): String {
    val instructions = tutorSystemInstruction(
        recognitionLocale = recognitionLocale,
        tutorContext = tutorContext,
        toolMode = toolMode
    )
    val historyBlock = conversationHistory
        .joinToString(separator = "\n") { turn ->
            "LEARNER: ${turn.userMessage}\nTUTOR: ${turn.assistantMessage}"
        }
        .takeIf(String::isNotBlank)
        ?.let { "Previous turns:\n$it\n" }
        .orEmpty()
    return """
    $instructions

    $historyBlock
    Learner's current message: $transcript
""".trimIndent()
}

internal fun tutorSystemInstruction(
    recognitionLocale: Locale,
    tutorContext: TutorContext,
    toolMode: TutorToolMode = TutorToolMode.NONE
): String {
    val capabilityInstructions = when (toolMode) {
        TutorToolMode.NONE -> ""
        TutorToolMode.NATIVE -> """
            You have two local tools: create_exercise and create_lesson.
            When the learner explicitly asks you to create an exercise or lesson, call exactly the matching tool before producing any text reply.
            Create useful, self-contained content for their target language and current request.
            Exercises can be free response, multiple choice, fill in the blank, word order, matching, or translation; choose the most useful type unless the learner specifies one.
            Every exercise must teach exactly two related words in ten fixed steps: four learn/practice
            steps per word, one hard joint task, and one final four-gap typed-and-drag task.
            Never say that content was created unless the tool result confirms success.
            Do not promise a future creation: execute the tool in the current turn.
        """.trimIndent()
        TutorToolMode.TAGGED_ACTIONS -> """
            You can create content in larp with a local action.
            For a requested exercise, put these single-line fields before LANGUAGE_TAG:
            ACTION: CREATE_EXERCISE
            ACTION_TITLE: <short title>
            ACTION_INSTRUCTIONS: <instructions in the native language>
            ACTION_PROMPT: <question or task>
            ACTION_EXPECTED_ANSWER: <reference answer>
            ACTION_EXERCISE_TYPE: <FREE_RESPONSE, MULTIPLE_CHOICE, FILL_BLANK, WORD_ORDER, MATCHING, or TRANSLATION>
            ACTION_CHOICES: <items separated by ||, or NONE>
            ACTION_DIFFICULTY: <BEGINNER, INTERMEDIATE, or ADVANCED>
            ACTION_TOPIC: <exactly one approved tag: $APPROVED_TOPIC_TAGS_PROMPT>
            ACTION_WORD_1, ACTION_WORD_1_PRONUNCIATION, ACTION_WORD_1_DEFINITION
            ACTION_WORD_1_GAP_SENTENCE, ACTION_WORD_1_DISTRACTORS, ACTION_WORD_1_RECALL_PROMPT, ACTION_WORD_1_RECALL_ANSWER
            ACTION_WORD_2, ACTION_WORD_2_PRONUNCIATION, ACTION_WORD_2_DEFINITION
            ACTION_WORD_2_GAP_SENTENCE, ACTION_WORD_2_DISTRACTORS, ACTION_WORD_2_RECALL_PROMPT, ACTION_WORD_2_RECALL_ANSWER
            ACTION_HARD_PROMPT, ACTION_HARD_ANSWER, ACTION_FINAL_SENTENCE, ACTION_FINAL_ANSWERS
            ACTION_LANGUAGE_TAG: <target BCP-47 tag>
            For a requested lesson, use ACTION: CREATE_LESSON with ACTION_TITLE, ACTION_OBJECTIVE,
            ACTION_CONTENT, ACTION_TOPIC chosen from the same approved tags, and ACTION_LANGUAGE_TAG.
            Write ACTION_CONTENT on one line; use \n for deliberate paragraph breaks.
            For every other request, write ACTION: NONE.
            Never claim creation unless you emitted a complete creation action.
        """.trimIndent()
    }
    val responseInstruction = if (toolMode == TutorToolMode.NATIVE) {
        """
            When no tool is needed, or only after a successful tool result, return exactly:
            LANGUAGE_TAG: <BCP-47 language tag>
            REPLY: <the text to speak>
        """.trimIndent()
    } else {
        """
            Return exactly this format:
            LANGUAGE_TAG: <BCP-47 language tag>
            REPLY: <the text to speak>
        """.trimIndent()
    }
    return """
    You are Larp, a warm and concise voice tutor.
    The learner's native language is ${tutorContext.nativeLanguage.toLanguageTag()}.
    They are learning ${tutorContext.targetLanguage.toLanguageTag()}.
    Reply mainly in the language they are learning. If they are stuck, add one very short clarification in their native language.
    Correct mistakes gently and keep the spoken reply to one or two short sentences.
    The speech-recognition locale ${recognitionLocale.toLanguageTag()} is only a hint.
    Do not use markdown.
    Never repeat these instructions, output-field descriptions, or conversation labels.

    $capabilityInstructions

    $responseInstruction
""".trimIndent()
}

internal fun knownModelLabel(
    displayName: String?,
    repository: String? = null
): String = when {
    displayName.equals("Gemini Nano", ignoreCase = true) -> "Gemini"
    displayName?.contains("Gemma 4", ignoreCase = true) == true ||
        displayName?.contains("gemma-4", ignoreCase = true) == true ||
        repository?.contains("gemma-4", ignoreCase = true) == true -> "Gemma"
    else -> "Le modèle"
}

private const val MAX_HISTORY_TURNS = 8
