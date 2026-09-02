package com.anis.larp.ui.freemode

import android.content.Context
import androidx.core.content.ContextCompat
import com.anis.larp.learning.Exercise
import com.anis.larp.learning.Lesson
import com.anis.larp.model.ModelPreferences
import com.anis.larp.model.PromptModelCatalog
import com.anis.larp.telemetry.Telemetry

/**
 * Process-wide owner of the voice pipeline.
 *
 * The Activity only observes this controller. The foreground service owns the
 * lifetime of an active conversation so locking the screen or navigating away
 * from the learning screen does not dispose the microphone, model, or TTS.
 */
class VoiceConversationController private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val recognizer = MlKitSpeechRecognizer(
        context = applicationContext,
        preferences = ModelPreferences(applicationContext),
        catalog = PromptModelCatalog(applicationContext)
    )

    val state = recognizer.state

    fun startConversation() {
        Telemetry.event("voice_conversation_requested")
        runCatching {
            ContextCompat.startForegroundService(
                applicationContext,
                VoiceConversationService.startIntent(applicationContext)
            )
        }.onFailure(recognizer::reportForegroundServiceFailure)
    }

    fun stopConversation() {
        Telemetry.event("voice_conversation_stop_requested")
        // Update the UI and release the audio pipeline immediately. The service
        // receives the same idempotent stop so it can remove its notification
        // and wake lock as well.
        recognizer.stop()
        runCatching {
            applicationContext.startService(
                VoiceConversationService.stopIntent(applicationContext)
            )
        }
    }

    fun preloadSelectedModel() = recognizer.preloadSelectedModel()

    fun dismissCreatedContent() = recognizer.dismissCreatedContent()

    fun sendTextMessage(message: String) = recognizer.submitTextMessage(message)

    suspend fun speakPracticeWord(text: String, languageTag: String) = Telemetry.operation(
        name = "practice_word_tts",
        attributes = mapOf("language" to languageTag)
    ) {
        recognizer.speakPracticeWord(text, languageTag)
    }

    suspend fun recognizePracticeAnswer(languageTag: String): String = Telemetry.operation(
        name = "practice_answer_recognition",
        attributes = mapOf("language" to languageTag)
    ) {
        recognizer.recognizePracticeAnswer(languageTag)
    }

    suspend fun remixExercise(
        exercise: Exercise,
        guidance: String,
        onPreparingModel: (String) -> Unit = {}
    ) = Telemetry.operation(
        name = "learning_content_remix",
        attributes = mapOf("content_kind" to "exercise")
    ) {
        recognizer.remixExercise(exercise, guidance, onPreparingModel)
    }

    suspend fun remixLesson(
        lesson: Lesson,
        guidance: String,
        onPreparingModel: (String) -> Unit = {}
    ) = Telemetry.operation(
        name = "learning_content_remix",
        attributes = mapOf("content_kind" to "lesson")
    ) {
        recognizer.remixLesson(lesson, guidance, onPreparingModel)
    }

    suspend fun answerLessonQuestion(
        lesson: Lesson,
        question: String,
        conversationHistory: List<ConversationTurn>,
        onPreparingModel: (String) -> Unit = {}
    ): GeneratedReply = Telemetry.operation(
        name = "lesson_question_reply",
        attributes = mapOf("language" to lesson.languageTag)
    ) {
        recognizer.answerLessonQuestion(
            lesson = lesson,
            question = question,
            conversationHistory = conversationHistory,
            onPreparingModel = onPreparingModel
        )
    }

    suspend fun importExerciseFromText(
        sourceText: String,
        onPreparingModel: (String) -> Unit = {}
    ) = Telemetry.operation(
        name = "exercise_import",
        attributes = mapOf("source" to "text")
    ) {
        recognizer.importExerciseFromText(sourceText, onPreparingModel)
    }

    suspend fun importExerciseFromYoutube(
        videoUrlOrId: String,
        onPreparingModel: (String) -> Unit = {}
    ) = Telemetry.operation(
        name = "exercise_import",
        attributes = mapOf("source" to "youtube")
    ) {
        recognizer.importExerciseFromYoutube(videoUrlOrId, onPreparingModel)
    }

    fun reportMicrophonePermissionDenied() = recognizer.reportPermissionDenied()

    fun reportNotificationPermissionDenied() =
        recognizer.reportNotificationPermissionDenied()

    internal fun startFromService() = recognizer.start()

    internal fun stopFromService() = recognizer.stop()

    internal fun suspendFromService() = recognizer.pauseConversation()

    internal fun reportServiceFailure(error: Throwable) =
        recognizer.reportForegroundServiceFailure(error)

    companion object {
        @Volatile
        private var instance: VoiceConversationController? = null

        fun getInstance(context: Context): VoiceConversationController =
            instance ?: synchronized(this) {
                instance ?: VoiceConversationController(context).also {
                    instance = it
                }
            }
    }
}
