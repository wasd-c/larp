package com.anis.larp.ui.freemode

import android.content.Context
import android.os.Build
import com.anis.larp.model.ModelPreferences
import com.anis.larp.model.PromptModelCatalog
import com.anis.larp.model.speechRecognitionLocaleFor
import com.anis.larp.learning.Exercise
import com.anis.larp.learning.LearningContentAction
import com.anis.larp.learning.LearningContentRepository
import com.anis.larp.learning.Lesson
import com.anis.larp.learning.YoutubeTranscriptProvider
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class MlKitSpeechRecognizer(
    context: Context,
    private val preferences: ModelPreferences,
    catalog: PromptModelCatalog
) {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessionStore = FreeModeSessionStore.getInstance(applicationContext)
    private val learningContentRepository =
        LearningContentRepository.getInstance(applicationContext)
    private val replyGenerator = PromptReplyRouter(
        context = applicationContext,
        preferences = preferences,
        catalog = catalog,
        onContentActionExecuted = ::onContentActionExecuted
    )
    private val speechSynthesizer = OfflineTextToSpeech(context)
    private val qwenSpeechRecognizer = QwenSpeechRecognizer.getInstance(applicationContext)
    private val youtubeTranscriptProvider = YoutubeTranscriptProvider()
    private val mutableState = MutableStateFlow(
        FreeModeUiState(
            locale = speechRecognitionLocaleFor(preferences.nativeLanguageTag),
            targetLocale = preferences.targetLanguage.locale
        )
    )
    private val mlKitRecognizerProvider = MlKitRecognizerProvider { message ->
        mutableState.update { it.copy(statusMessage = message) }
    }
    private var recognitionJob: Job? = null
    private var silenceJob: Job? = null
    private var restartJob: Job? = null
    private var preloadJob: Job? = null
    private var activeRecognizer: SpeechRecognizer? = null
    private var processingUtterance = false
    private var conversationActive = false
    private var conversationPaused = false

    val state: StateFlow<FreeModeUiState> = mutableState.asStateFlow()

    init {
        preloadSelectedModel()
    }

    fun preloadSelectedModel() {
        preloadJob?.cancel()
        preloadJob = scope.launch {
            val modelLabel = replyGenerator.selectedModelLabel()
            updateIdleStatus(
                message = "$modelLabel se prépare en arrière-plan…",
                modelLabel = modelLabel
            )
            mutableState.update {
                it.copy(
                    modelsReady = false,
                    modelReadinessError = null,
                    targetLocale = preferences.targetLanguage.locale
                )
            }
            try {
                if (preferences.sttModelId == ModelPreferences.STT_QWEN_3_ASR) {
                    qwenSpeechRecognizer.preload { message ->
                        updateIdleStatus(message, modelLabel)
                    }
                } else {
                    qwenSpeechRecognizer.release()
                    val locale = speechRecognitionLocaleFor(preferences.nativeLanguageTag)
                    val selected = mlKitRecognizerProvider.select(
                        locale,
                        preferences.sttModelId
                    ) ?: throw IllegalStateException(
                        "Le modèle de reconnaissance vocale sélectionné n'est pas disponible."
                    )
                    selected.recognizer.close()
                }
                preferences.ttsVoiceName?.let { selectedVoice ->
                    updateIdleStatus("Préparation de la voix hors ligne…", modelLabel)
                    speechSynthesizer.preload(
                        requestedLocale = preferences.targetLanguage.locale,
                        selectedVoiceName = selectedVoice
                    )
                }
                val readyMessage = replyGenerator.preloadSelectedModel { message ->
                    updateIdleStatus(message, modelLabel)
                } ?: throw IllegalStateException(
                    "$modelLabel n'est pas encore téléchargé."
                )
                sessionStore.recordModelReady(modelLabel, readyMessage)
                updateIdleStatus(
                    message = readyMessage,
                    modelLabel = modelLabel
                )
                mutableState.update {
                    it.copy(modelsReady = true, modelReadinessError = null)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                sessionStore.recordError("model_preload", error)
                updateIdleStatus(
                    message = error.message
                        ?: "$modelLabel n'a pas pu être préparé.",
                    modelLabel = modelLabel
                )
                mutableState.update {
                    it.copy(
                        modelsReady = false,
                        modelReadinessError = error.message
                            ?: "Les modèles sélectionnés n'ont pas pu être préparés."
                    )
                }
            }
        }
    }

    fun dismissCreatedContent() {
        mutableState.update { it.copy(createdContent = null) }
    }

    fun submitTextMessage(message: String) {
        val cleanMessage = message.trim().take(MAX_TEXT_MESSAGE_CHARACTERS)
        if (cleanMessage.isBlank() || !mutableState.value.modelsReady) return
        scope.launch { processTextMessage(cleanMessage) }
    }

    suspend fun speakPracticeWord(text: String, languageTag: String) {
        speechSynthesizer.speak(
            text = text,
            requestedLocale = speechRecognitionLocaleFor(languageTag),
            selectedVoiceName = preferences.ttsVoiceName
        )
    }

    suspend fun recognizePracticeAnswer(languageTag: String): String {
        check(!conversationActive) {
            "Terminez la conversation Libre avant d'utiliser le micro de l'exercice."
        }
        val locale = speechRecognitionLocaleFor(languageTag)
        if (preferences.sttModelId == ModelPreferences.STT_QWEN_3_ASR) {
            return qwenSpeechRecognizer.recognize(locale)
        }
        val selected = mlKitRecognizerProvider.select(
            locale,
            preferences.sttModelId
        ) ?: throw IllegalStateException(
            "La reconnaissance vocale sur l'appareil n'est pas disponible pour ${locale.toLanguageTag()}."
        )
        return try {
            var recognized = ""
            val request = speechRecognizerRequest {
                audioSource = AudioSource.fromMic()
            }
            withTimeout(30_000) {
                selected.recognizer.startRecognition(request).first { response ->
                    when (response) {
                        is SpeechRecognizerResponse.PartialTextResponse -> {
                            recognized = response.text.trim()
                            false
                        }
                        is SpeechRecognizerResponse.FinalTextResponse -> {
                            recognized = response.text.trim()
                            true
                        }
                        is SpeechRecognizerResponse.ErrorResponse -> throw response.e
                        is SpeechRecognizerResponse.CompletedResponse -> true
                    }
                }
            }
            recognized.ifBlank {
                throw IllegalStateException("Aucune parole n'a été reconnue.")
            }
        } finally {
            selected.recognizer.close()
        }
    }

    suspend fun remixExercise(
        exercise: Exercise,
        guidance: String,
        onPreparingModel: (String) -> Unit = {}
    ) = replyGenerator.remixExercise(exercise, guidance, onPreparingModel)

    suspend fun remixLesson(
        lesson: Lesson,
        guidance: String,
        onPreparingModel: (String) -> Unit = {}
    ) = replyGenerator.remixLesson(lesson, guidance, onPreparingModel)

    suspend fun answerLessonQuestion(
        lesson: Lesson,
        question: String,
        conversationHistory: List<ConversationTurn>,
        onPreparingModel: (String) -> Unit = {}
    ): GeneratedReply = replyGenerator.answerLessonQuestion(
        lesson = lesson,
        question = question,
        conversationHistory = conversationHistory,
        onPreparingModel = onPreparingModel
    )

    suspend fun importExerciseFromText(
        sourceText: String,
        onPreparingModel: (String) -> Unit = {}
    ) = replyGenerator.importExerciseFromText(sourceText, onPreparingModel)

    suspend fun importExerciseFromYoutube(
        videoUrlOrId: String,
        onPreparingModel: (String) -> Unit = {}
    ) {
        onPreparingModel("Récupération de la transcription YouTube…")
        val transcript = youtubeTranscriptProvider.fetch(
            videoUrlOrId = videoUrlOrId,
            preferredLanguages = listOf(
                preferences.targetLanguage.locale.toLanguageTag(),
                preferences.nativeLanguageTag,
                "en"
            )
        )
        onPreparingModel("La transcription est prête. Création du quiz…")
        replyGenerator.importExerciseFromYoutube(transcript, onPreparingModel)
    }

    fun start() {
        if (!mutableState.value.modelsReady) {
            mutableState.update {
                it.copy(
                    phase = SpeechPhase.PREPARING,
                    statusMessage = "Les modèles doivent finir de charger avant de commencer."
                )
            }
            preloadSelectedModel()
            return
        }
        if (conversationActive) {
            resumeConversation()
            return
        }
        if (recognitionJob?.isActive == true) return

        sessionStore.beginOrResume(sessionMetadata())
        conversationActive = true
        conversationPaused = false
        startListeningTurn(clearReply = true)
    }

    private fun startListeningTurn(clearReply: Boolean) {
        if (!conversationActive || conversationPaused) return

        silenceJob?.cancel()
        restartJob?.cancel()
        speechSynthesizer.stop()
        processingUtterance = false
        recognitionJob = scope.launch {
            val locale = speechRecognitionLocaleFor(preferences.nativeLanguageTag)
            mutableState.update { state ->
                state.copy(
                    phase = SpeechPhase.PREPARING,
                    locale = locale,
                    targetLocale = preferences.targetLanguage.locale,
                    committedTranscript = "",
                    partialTranscript = "",
                    statusMessage = "Préparation de la reconnaissance vocale…",
                    aiReply = if (clearReply) "" else state.aiReply,
                    replyLocale = if (clearReply) null else state.replyLocale,
                    conversationActive = true
                )
            }

            var recognizer: SpeechRecognizer? = null
            try {
                if (preferences.sttModelId == ModelPreferences.STT_QWEN_3_ASR) {
                    recognizeQwenTurn(locale)
                    return@launch
                }
                val selected = mlKitRecognizerProvider.select(
                    locale,
                    preferences.sttModelId
                )
                if (selected == null) {
                    val error = IllegalStateException(
                        "La reconnaissance vocale sur l'appareil n'est pas disponible pour ${locale.toLanguageTag()}."
                    )
                    sessionStore.recordError("speech_recognition_availability", error)
                    mutableState.update {
                        it.copy(
                            phase = SpeechPhase.ERROR,
                            conversationActive = true,
                            statusMessage = "La reconnaissance vocale sur l'appareil n'est pas disponible pour ${locale.toLanguageTag()}."
                        )
                    }
                    scheduleListeningRestart()
                    return@launch
                }

                recognizer = selected.recognizer
                activeRecognizer = recognizer
                mutableState.update {
                    it.copy(
                        phase = SpeechPhase.LISTENING,
                        recognitionMode = selected.label,
                        statusMessage = "Écoute et transcription sur l'appareil"
                    )
                }
                sessionStore.recordRecognitionReady(
                    localeTag = locale.toLanguageTag(),
                    mode = selected.label
                )

                val request = speechRecognizerRequest {
                    audioSource = AudioSource.fromMic()
                }
                recognizer.startRecognition(request).collect { response ->
                    when (response) {
                        is SpeechRecognizerResponse.PartialTextResponse -> {
                            mutableState.update {
                                it.copy(partialTranscript = response.text.trim())
                            }
                            scheduleReplyAfterSilence(delayMillis = 1_200)
                        }

                        is SpeechRecognizerResponse.FinalTextResponse -> {
                            mutableState.update {
                                it.copy(
                                    committedTranscript = appendText(
                                        it.committedTranscript,
                                        response.text
                                    ),
                                    partialTranscript = ""
                                )
                            }
                            scheduleReplyAfterSilence(delayMillis = 800)
                        }

                        is SpeechRecognizerResponse.ErrorResponse -> {
                            throw response.e
                        }

                        is SpeechRecognizerResponse.CompletedResponse -> {
                            if (mutableState.value.visibleTranscript.isBlank()) {
                                mutableState.update {
                                    it.copy(
                                        phase = SpeechPhase.PREPARING,
                                        conversationActive = true,
                                        statusMessage = "Aucune parole détectée, je vous réécoute…"
                                    )
                                }
                                scheduleListeningRestart(delayMillis = 400)
                            } else {
                                mutableState.update {
                                    it.copy(statusMessage = "Silence détecté…")
                                }
                                scheduleReplyAfterSilence(delayMillis = 250)
                            }
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                if (!processingUtterance) {
                    sessionStore.recordError("speech_recognition", error)
                    mutableState.update {
                        it.copy(
                            phase = SpeechPhase.ERROR,
                            conversationActive = conversationActive,
                            statusMessage = error.message
                                ?: "Impossible de démarrer la reconnaissance vocale."
                        )
                    }
                    scheduleListeningRestart()
                }
            } finally {
                if (activeRecognizer === recognizer) {
                    activeRecognizer = null
                }
                recognizer?.close()
            }
        }
    }

    private suspend fun recognizeQwenTurn(locale: Locale) {
        mutableState.update {
            it.copy(
                phase = SpeechPhase.PREPARING,
                recognitionMode = "Qwen",
                statusMessage = "Qwen ASR se prépare…"
            )
        }
        val transcription = qwenSpeechRecognizer.recognize(locale) {
            mutableState.update {
                it.copy(
                    phase = SpeechPhase.LISTENING,
                    recognitionMode = "Qwen",
                    statusMessage = "Écoute et transcription Qwen sur l'appareil"
                )
            }
            sessionStore.recordRecognitionReady(
                localeTag = locale.toLanguageTag(),
                mode = "Qwen"
            )
        }
        mutableState.update {
            it.copy(
                committedTranscript = appendText(it.committedTranscript, transcription),
                partialTranscript = "",
                statusMessage = "Transcription terminée…"
            )
        }
        scheduleReplyAfterSilence(delayMillis = 100)
    }

    fun pauseConversation() {
        if (!conversationActive || conversationPaused) return
        conversationPaused = true
        sessionStore.recordPause("service_or_system_pause")
        cancelActiveTurn(
            conversationRemainsActive = true,
            statusMessage = "Conversation en pause"
        )
    }

    fun resumeConversation() {
        if (!conversationActive || !conversationPaused) return
        conversationPaused = false
        sessionStore.beginOrResume(sessionMetadata())
        startListeningTurn(clearReply = false)
    }

    fun stop() {
        val wasActive = conversationActive ||
            mutableState.value.isActive ||
            recognitionJob?.isActive == true ||
            silenceJob?.isActive == true
        conversationActive = false
        conversationPaused = false
        replyGenerator.endConversation()
        sessionStore.endExplicitly()
        if (!wasActive) return
        cancelActiveTurn(
            conversationRemainsActive = false,
            statusMessage = "Conversation terminée"
        )
    }

    private fun cancelActiveTurn(
        conversationRemainsActive: Boolean,
        statusMessage: String
    ) {
        val recognizer = activeRecognizer
        val recognition = recognitionJob
        silenceJob?.cancel()
        silenceJob = null
        restartJob?.cancel()
        restartJob = null
        recognitionJob = null
        activeRecognizer = null
        processingUtterance = false
        speechSynthesizer.stop()
        qwenSpeechRecognizer.cancelRecognition()
        mutableState.update {
            it.copy(
                phase = SpeechPhase.IDLE,
                committedTranscript = it.visibleTranscript,
                partialTranscript = "",
                statusMessage = statusMessage,
                conversationActive = conversationRemainsActive
            )
        }
        scope.launch {
            recognition?.cancel()
            runCatching { recognizer?.stopRecognition() }
            recognizer?.close()
        }
    }

    fun reportPermissionDenied() {
        sessionStore.recordError(
            "microphone_permission",
            SecurityException("Autorisation du microphone refusée.")
        )
        mutableState.update {
            it.copy(
                phase = SpeechPhase.ERROR,
                statusMessage = "L'autorisation du microphone est nécessaire pour transcrire votre voix."
            )
        }
    }

    fun reportNotificationPermissionDenied() {
        sessionStore.recordError(
            "notification_permission",
            SecurityException("Autorisation des notifications refusée.")
        )
        mutableState.update {
            it.copy(
                phase = SpeechPhase.ERROR,
                statusMessage =
                    "Autorisez les notifications pour garder l’écoute active écran verrouillé et pouvoir l’arrêter."
            )
        }
    }

    fun reportForegroundServiceFailure(error: Throwable) {
        sessionStore.recordError("foreground_service", error)
        mutableState.update {
            it.copy(
                phase = SpeechPhase.ERROR,
                statusMessage = error.message
                    ?: "Android n'a pas pu démarrer l'écoute en arrière-plan."
            )
        }
    }

    fun close() {
        recognitionJob?.cancel()
        silenceJob?.cancel()
        restartJob?.cancel()
        preloadJob?.cancel()
        activeRecognizer?.close()
        activeRecognizer = null
        qwenSpeechRecognizer.release()
        speechSynthesizer.close()
        replyGenerator.close()
        conversationActive = false
        conversationPaused = false
        scope.cancel()
    }

    private fun updateIdleStatus(message: String, modelLabel: String) {
        mutableState.update { state ->
            if (state.phase == SpeechPhase.IDLE) {
                state.copy(
                    statusMessage = message,
                    promptModelName = modelLabel
                )
            } else {
                state
            }
        }
    }

    private fun scheduleReplyAfterSilence(delayMillis: Long) {
        if (processingUtterance) return
        silenceJob?.cancel()
        silenceJob = scope.launch {
            delay(delayMillis)
            processUtterance()
        }
    }

    private fun scheduleListeningRestart(delayMillis: Long = 1_200L) {
        if (!conversationActive || conversationPaused) return
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(delayMillis)
            if (conversationActive && !conversationPaused) {
                startListeningTurn(clearReply = false)
            }
        }
    }

    private suspend fun processUtterance() {
        val currentState = mutableState.value
        val transcript = currentState.visibleTranscript
        if (
            processingUtterance ||
            currentState.phase != SpeechPhase.LISTENING ||
            transcript.isBlank()
        ) {
            return
        }

        processingUtterance = true
        val recognizer = activeRecognizer
        val recognition = recognitionJob
        recognitionJob = null
        activeRecognizer = null
        recognition?.cancel()
        runCatching { recognizer?.stopRecognition() }
        recognizer?.close()

        val modelLabel = replyGenerator.selectedModelLabel()
        sessionStore.recordUserUtterance(
            text = transcript,
            localeTag = currentState.locale.toLanguageTag(),
            recognitionMode = currentState.recognitionMode
        )
        mutableState.update {
            it.copy(
                phase = SpeechPhase.THINKING,
                committedTranscript = transcript,
                partialTranscript = "",
                promptModelName = modelLabel,
                statusMessage = null,
                thinkingWord = THINKING_WORDS.random(Random.Default),
                chatMessages = it.chatMessages.appendChatMessage(
                    TutorChatMessage(ChatMessageAuthor.LEARNER, transcript)
                )
            )
        }

        var restartImmediately = false
        var restartAfterFailure = false
        var failureStage = "reply_generation"
        try {
            val generatedReply = replyGenerator.generateReply(
                transcript = transcript,
                recognitionLocale = currentState.locale,
                onPreparingModel = { message ->
                    mutableState.update {
                        it.copy(statusMessage = message)
                    }
                }
            )
            mutableState.update {
                it.copy(
                    phase = SpeechPhase.SPEAKING,
                    aiReply = generatedReply.text,
                    replyLocale = generatedReply.locale,
                    promptModelName = generatedReply.modelName,
                    promptAcceleration = generatedReply.acceleration,
                    thinkingWord = null,
                    statusMessage = "Réponse vocale en ${generatedReply.locale.displayLanguage}",
                    targetLocale = preferences.targetLanguage.locale,
                    chatMessages = it.chatMessages.appendChatMessage(
                        TutorChatMessage(ChatMessageAuthor.TUTOR, generatedReply.text)
                    )
                )
            }
            sessionStore.recordAssistantReply(
                reply = generatedReply,
                ttsVoiceName = preferences.ttsVoiceName
            )
            failureStage = "text_to_speech"
            val spokenVoice = speechSynthesizer.speak(
                text = generatedReply.text,
                requestedLocale = generatedReply.locale,
                selectedVoiceName = preferences.ttsVoiceName
            )
            sessionStore.recordTtsCompleted(spokenVoice.name)
            mutableState.update {
                it.copy(
                    phase = if (conversationActive && !conversationPaused) {
                        SpeechPhase.PREPARING
                    } else {
                        SpeechPhase.IDLE
                    },
                    statusMessage = if (conversationActive && !conversationPaused) {
                        "À vous, je vous écoute de nouveau…"
                    } else {
                        "Réponse terminée"
                    },
                    conversationActive = conversationActive
                )
            }
            restartImmediately = conversationActive && !conversationPaused
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            sessionStore.recordError(failureStage, error)
            mutableState.update {
                it.copy(
                    phase = SpeechPhase.ERROR,
                    thinkingWord = null,
                    conversationActive = conversationActive,
                    statusMessage = error.message
                        ?: "La réponse sur l'appareil a échoué."
                )
            }
            restartAfterFailure = conversationActive && !conversationPaused
        } finally {
            processingUtterance = false
        }
        when {
            restartImmediately && conversationActive && !conversationPaused -> {
                startListeningTurn(clearReply = false)
            }
            restartAfterFailure && conversationActive && !conversationPaused -> {
                scheduleListeningRestart()
            }
        }
    }

    private suspend fun processTextMessage(message: String) {
        if (processingUtterance) return
        processingUtterance = true
        silenceJob?.cancel()
        silenceJob = null
        restartJob?.cancel()
        restartJob = null
        val recognizer = activeRecognizer
        val recognition = recognitionJob
        recognitionJob = null
        activeRecognizer = null
        recognition?.cancel()
        runCatching { recognizer?.stopRecognition() }
        recognizer?.close()
        qwenSpeechRecognizer.cancelRecognition()
        speechSynthesizer.stop()

        sessionStore.beginOrResume(sessionMetadata())
        sessionStore.recordUserUtterance(
            text = message,
            localeTag = preferences.nativeLanguageTag,
            recognitionMode = "Texte"
        )
        val modelLabel = replyGenerator.selectedModelLabel()
        mutableState.update {
            it.copy(
                phase = SpeechPhase.THINKING,
                committedTranscript = message,
                partialTranscript = "",
                promptModelName = modelLabel,
                statusMessage = null,
                thinkingWord = THINKING_WORDS.random(Random.Default),
                chatMessages = it.chatMessages.appendChatMessage(
                    TutorChatMessage(ChatMessageAuthor.LEARNER, message)
                )
            )
        }

        var restartVoice = false
        try {
            val generatedReply = replyGenerator.generateReply(
                transcript = message,
                recognitionLocale = Locale.forLanguageTag(preferences.nativeLanguageTag),
                onPreparingModel = { preparation ->
                    mutableState.update { it.copy(statusMessage = preparation) }
                }
            )
            sessionStore.recordAssistantReply(
                reply = generatedReply,
                ttsVoiceName = null,
                delivery = "text"
            )
            mutableState.update {
                it.copy(
                    phase = if (conversationActive && !conversationPaused) {
                        SpeechPhase.PREPARING
                    } else {
                        SpeechPhase.IDLE
                    },
                    aiReply = generatedReply.text,
                    replyLocale = generatedReply.locale,
                    promptModelName = generatedReply.modelName,
                    promptAcceleration = generatedReply.acceleration,
                    thinkingWord = null,
                    targetLocale = preferences.targetLanguage.locale,
                    statusMessage = if (conversationActive && !conversationPaused) {
                        "Réponse écrite · reprise de l'écoute…"
                    } else {
                        "Réponse écrite"
                    },
                    chatMessages = it.chatMessages.appendChatMessage(
                        TutorChatMessage(ChatMessageAuthor.TUTOR, generatedReply.text)
                    )
                )
            }
            restartVoice = conversationActive && !conversationPaused
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            sessionStore.recordError("text_reply_generation", error)
            mutableState.update {
                it.copy(
                    phase = SpeechPhase.ERROR,
                    thinkingWord = null,
                    statusMessage = error.message
                        ?: "La réponse écrite sur l'appareil a échoué."
                )
            }
        } finally {
            processingUtterance = false
        }
        if (restartVoice && conversationActive && !conversationPaused) {
            startListeningTurn(clearReply = false)
        }
    }

    private fun onContentActionExecuted(action: LearningContentAction) {
        sessionStore.recordToolAction(action)
        val createdContent = when (action) {
            is LearningContentAction.CreateLessonContent -> {
                val exercise = learningContentRepository.state.value.exercises
                    .firstOrNull()
                    ?: return
                CreatedLearningContent(
                    id = exercise.id,
                    kind = CreatedLearningContentKind.EXERCISE,
                    title = exercise.title,
                    description = exercise.lessonContent?.targets
                        ?.joinToString { it.text }
                        .orEmpty(),
                    topic = exercise.topic,
                    difficulty = exercise.difficulty.frenchLabel
                )
            }
            is LearningContentAction.CreateExercise -> {
                val exercise = learningContentRepository.state.value.exercises
                    .firstOrNull()
                    ?: return
                CreatedLearningContent(
                    id = exercise.id,
                    kind = CreatedLearningContentKind.EXERCISE,
                    title = exercise.title,
                    description = exercise.instructions,
                    topic = exercise.topic,
                    difficulty = exercise.difficulty.frenchLabel
                )
            }
            is LearningContentAction.CreateLesson -> {
                val lesson = learningContentRepository.state.value.lessons
                    .firstOrNull()
                    ?: return
                CreatedLearningContent(
                    id = lesson.id,
                    kind = CreatedLearningContentKind.LESSON,
                    title = lesson.title,
                    description = lesson.objective,
                    topic = lesson.topic
                )
            }
        }
        mutableState.update { it.copy(createdContent = createdContent) }
    }

    private companion object {
        val THINKING_WORDS = listOf(
            "Thinking",
            "Cerebrating",
            "Pondering",
            "Cogitating",
            "Ruminating",
            "Deliberating",
            "Musing",
            "Ideating",
            "Contemplating",
            "Flibbertigibbet"
        )
        const val MAX_TEXT_MESSAGE_CHARACTERS = 2_000
    }

    private fun sessionMetadata(): FreeModeSessionMetadata {
        val appVersion = runCatching {
            applicationContext.packageManager
                .getPackageInfo(applicationContext.packageName, 0)
                .versionName
        }.getOrNull().orEmpty().ifBlank { "unknown" }
        return FreeModeSessionMetadata(
            nativeLanguageTag = preferences.nativeLanguageTag,
            targetLanguageTag = preferences.targetLanguage.languageTag,
            promptModelId = preferences.promptModelId,
            promptModelLabel = replyGenerator.selectedModelLabel(),
            sttModelId = preferences.sttModelId ?: "automatic_offline",
            ttsVoiceName = preferences.ttsVoiceName ?: "automatic_offline",
            appVersion = appVersion,
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            androidSdk = Build.VERSION.SDK_INT
        )
    }

}

private fun List<TutorChatMessage>.appendChatMessage(
    message: TutorChatMessage
): List<TutorChatMessage> = (this + message).takeLast(MAX_VISIBLE_CHAT_MESSAGES)

private const val MAX_VISIBLE_CHAT_MESSAGES = 20

internal fun appendText(existing: String, addition: String): String =
    listOf(existing.trim(), addition.trim())
        .filter(String::isNotBlank)
        .joinToString(separator = " ")
