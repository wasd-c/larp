package com.anis.larp.ui.freemode

import android.content.Context
import androidx.core.content.ContextCompat
import com.anis.larp.learning.Exercise
import com.anis.larp.learning.Lesson
import com.anis.larp.model.ModelPreferences
import com.anis.larp.model.PromptModelCatalog

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
        runCatching {
            ContextCompat.startForegroundService(
                applicationContext,
                VoiceConversationService.startIntent(applicationContext)
            )
        }.onFailure(recognizer::reportForegroundServiceFailure)
    }

    fun stopConversation() {
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

    suspend fun speakPracticeWord(text: String, languageTag: String) =
        recognizer.speakPracticeWord(text, languageTag)

    suspend fun recognizePracticeAnswer(languageTag: String): String =
        recognizer.recognizePracticeAnswer(languageTag)

    suspend fun remixExercise(
        exercise: Exercise,
        guidance: String,
        onPreparingModel: (String) -> Unit = {}
    ) = recognizer.remixExercise(exercise, guidance, onPreparingModel)

    suspend fun remixLesson(
        lesson: Lesson,
        guidance: String,
        onPreparingModel: (String) -> Unit = {}
    ) = recognizer.remixLesson(lesson, guidance, onPreparingModel)

    suspend fun importExerciseFromText(
        sourceText: String,
        onPreparingModel: (String) -> Unit = {}
    ) = recognizer.importExerciseFromText(sourceText, onPreparingModel)

    suspend fun importExerciseFromYoutube(
        videoUrlOrId: String,
        onPreparingModel: (String) -> Unit = {}
    ) = recognizer.importExerciseFromYoutube(videoUrlOrId, onPreparingModel)

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
