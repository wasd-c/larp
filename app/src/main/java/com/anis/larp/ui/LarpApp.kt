package com.anis.larp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.anis.larp.learning.LearningContentRepository
import com.anis.larp.ui.components.AppDestination
import com.anis.larp.ui.components.ExpressiveNavigationBar
import com.anis.larp.ui.freemode.FreeModeScreen
import com.anis.larp.ui.freemode.CreatedLearningContentKind
import com.anis.larp.ui.freemode.VoiceConversationController
import com.anis.larp.model.ModelDownloadManager
import com.anis.larp.model.ModelDownloadWorker
import com.anis.larp.model.ModelPreferences
import com.anis.larp.model.PromptModelCatalog
import com.anis.larp.model.DeviceAccelerationProfile
import com.anis.larp.model.QwenAsrModel
import com.anis.larp.telemetry.Telemetry
import com.anis.larp.telemetry.TelemetryConsent
import com.anis.larp.telemetry.TelemetryGate
import com.anis.larp.telemetry.TelemetryPreferences
import com.anis.larp.ui.onboarding.OnboardingScreen
import com.anis.larp.ui.onboarding.PromptSetup
import com.anis.larp.ui.settings.ModelSettingsScreen
import com.anis.larp.ui.telemetry.TelemetryConsentScreen

@Composable
fun LarpApp(
    animationsEnabled: Boolean = true,
    skipOnboarding: Boolean = false,
    skipModelReadiness: Boolean = false,
    skipTelemetryConsent: Boolean = false
) {
    val context = LocalContext.current
    val telemetryPreferences = remember(context.applicationContext) {
        TelemetryPreferences(context.applicationContext)
    }
    var telemetryConsent by remember {
        mutableStateOf(telemetryPreferences.consent)
    }
    if (TelemetryGate.shouldShowConsent(
            consent = telemetryConsent,
            configured = Telemetry.isConfigured,
            skipped = skipTelemetryConsent
        )
    ) {
        TelemetryConsentScreen(
            onAccept = {
                Telemetry.setConsent(
                    context = context.applicationContext,
                    consent = TelemetryConsent.GRANTED,
                    source = "first_launch"
                )
                telemetryConsent = TelemetryConsent.GRANTED
            },
            onDecline = {
                Telemetry.setConsent(
                    context = context.applicationContext,
                    consent = TelemetryConsent.DENIED,
                    source = "first_launch"
                )
                telemetryConsent = TelemetryConsent.DENIED
            }
        )
        return
    }

    val preferences = remember(context.applicationContext) {
        ModelPreferences(context.applicationContext)
    }
    val promptCatalog = remember(context.applicationContext) {
        PromptModelCatalog(context.applicationContext)
    }
    val downloadManager = remember(context.applicationContext) {
        ModelDownloadManager(context.applicationContext)
    }
    var onboardingComplete by remember {
        mutableStateOf(skipOnboarding || preferences.onboardingComplete)
    }
    var modelSettingsOpen by remember { mutableStateOf(false) }
    var downloadSchedulingComplete by remember { mutableStateOf(false) }
    val modelDownloads by remember(context.applicationContext) {
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosByTagFlow(ModelDownloadManager.DOWNLOAD_TAG)
    }.collectAsState(initial = emptyList())

    LaunchedEffect(Unit) {
        Telemetry.event("app_ui_started")
    }

    fun enqueueMissingSelectedModels() {
        val profile = DeviceAccelerationProfile.detect()
        val legacyGemmaModelId = PromptModelCatalog.remoteModelId(
            PromptModelCatalog.GEMMA_4_REPOSITORY
        )
        val gemmaModelId = PromptModelCatalog.remoteModelId(
            PromptModelCatalog.GEMMA_4_REPOSITORY,
            profile.gemmaArtifactFileName
        )
        if (
            preferences.promptModelId in setOf(gemmaModelId, legacyGemmaModelId) &&
            promptCatalog.find(preferences.promptModelId) == null
        ) {
            downloadManager.enqueueGemma4()
        }
        if (
            preferences.sttModelId == ModelPreferences.STT_QWEN_3_ASR &&
            !QwenAsrModel.isAvailable(context.applicationContext)
        ) {
            downloadManager.enqueueQwenAsr()
        }
    }

    fun closeModelSettings() {
        if (!modelSettingsOpen) return

        Telemetry.event("settings_closed")
        modelSettingsOpen = false
        downloadSchedulingComplete = false
        enqueueMissingSelectedModels()
        downloadSchedulingComplete = true
        VoiceConversationController.getInstance(context.applicationContext)
            .preloadSelectedModel()
    }

    fun openModelSettings() {
        if (modelSettingsOpen) return
        Telemetry.event("settings_opened")
        modelSettingsOpen = true
    }

    LaunchedEffect(
        onboardingComplete,
        preferences.promptModelId,
        preferences.sttModelId
    ) {
        downloadSchedulingComplete = false
        if (onboardingComplete) enqueueMissingSelectedModels()
        downloadSchedulingComplete = true
    }

    if (!onboardingComplete) {
        OnboardingScreen(
            promptCatalog = promptCatalog,
            onComplete = { selection ->
                when (val setup = selection.promptSetup) {
                    PromptSetup.GeminiNano,
                    is PromptSetup.Imported -> Unit

                    is PromptSetup.HuggingFace -> {
                        if (promptCatalog.find(setup.modelId) == null) {
                            if (setup.isDefaultGemma4) {
                                downloadManager.enqueueGemma4()
                            } else {
                                downloadManager.enqueue(
                                    repository = setup.repository,
                                    displayName = setup.displayName,
                                    requestedFileName = setup.requestedFileName,
                                    accelerationKind = if (
                                        setup.requestedFileName?.contains(
                                            "qualcomm",
                                            ignoreCase = true
                                        ) == true
                                    ) {
                                        com.anis.larp.model.AccelerationKind.NPU
                                    } else {
                                        com.anis.larp.model.AccelerationKind.AUTO
                                    }
                                )
                            }
                        }
                    }
                }
                if (
                    selection.sttModelId == ModelPreferences.STT_QWEN_3_ASR &&
                    !QwenAsrModel.isAvailable(context.applicationContext)
                ) {
                    downloadManager.enqueueQwenAsr()
                }
                preferences.completeOnboarding(
                    nativeLanguageTag = selection.nativeLanguageTag,
                    targetLanguage = selection.targetLanguage,
                    promptModelId = selection.promptSetup.modelId,
                    sttModelId = selection.sttModelId
                )
                Telemetry.event(
                    name = "onboarding_completed",
                    attributes = mapOf(
                        "language" to selection.targetLanguage.languageTag,
                        "model_id" to selection.promptSetup.modelId,
                        "stt_engine" to selection.sttModelId
                    )
                )
                onboardingComplete = true
            }
        )
        return
    }

    BackHandler(
        enabled = modelSettingsOpen,
        onBack = ::closeModelSettings
    )

    if (modelSettingsOpen) {
        ModelSettingsScreen(
            preferences = preferences,
            promptCatalog = promptCatalog,
            onBack = ::closeModelSettings
        )
        return
    }

    val downloadRevision = modelDownloads.map { work ->
        Triple(work.id, work.state, work.progress)
    }
    val promptArtifactAvailable = remember(
        preferences.promptModelId,
        downloadRevision
    ) {
        preferences.promptModelId == ModelPreferences.PROMPT_GEMINI_NANO ||
            promptCatalog.find(preferences.promptModelId) != null
    }
    val speechArtifactAvailable = remember(
        preferences.sttModelId,
        downloadRevision
    ) {
        preferences.sttModelId != ModelPreferences.STT_QWEN_3_ASR ||
            QwenAsrModel.isAvailable(context.applicationContext)
    }
    if (!promptArtifactAvailable || !speechArtifactAvailable) {
        val relevantDownloads = modelDownloads.filter { work ->
            preferences.promptModelId in work.tags ||
                preferences.sttModelId?.let(work.tags::contains) == true
        }
        val activeDownload = relevantDownloads.any { !it.state.isFinished }
        val failedDownload = relevantDownloads.lastOrNull {
            it.state == WorkInfo.State.FAILED || it.state == WorkInfo.State.CANCELLED
        }
        val profile = DeviceAccelerationProfile.detect()
        val selectedPromptCanDownloadAutomatically = preferences.promptModelId in setOf(
            PromptModelCatalog.remoteModelId(PromptModelCatalog.GEMMA_4_REPOSITORY),
            PromptModelCatalog.remoteModelId(
                PromptModelCatalog.GEMMA_4_REPOSITORY,
                profile.gemmaArtifactFileName
            )
        )
        val automaticDownloadExpected = !speechArtifactAvailable ||
            (!promptArtifactAvailable && selectedPromptCanDownloadAutomatically)
        ModelReadinessScreen(
            downloads = relevantDownloads,
            statusMessage = when {
                !speechArtifactAvailable -> "Téléchargement des deux fichiers Qwen ASR…"
                else -> "Téléchargement du modèle professeur sélectionné…"
            },
            errorMessage = if (
                downloadSchedulingComplete &&
                    !activeDownload &&
                    (relevantDownloads.isNotEmpty() || !automaticDownloadExpected)
            ) {
                failedDownload?.outputData?.getString(ModelDownloadWorker.KEY_ERROR)
                    ?: "Le modèle sélectionné n'est pas disponible. Réessayez ou choisissez un modèle installé."
            } else {
                null
            },
            onRetry = {
                Telemetry.event("model_download_retried")
                downloadSchedulingComplete = false
                enqueueMissingSelectedModels()
                downloadSchedulingComplete = true
            },
            onOpenSettings = ::openModelSettings
        )
        return
    }

    val conversationController = remember(context.applicationContext) {
        VoiceConversationController.getInstance(context.applicationContext)
    }
    val learningContentRepository = remember(context.applicationContext) {
        LearningContentRepository.getInstance(context.applicationContext)
    }
    val uiState by conversationController.state.collectAsState()
    val learningContent by learningContentRepository.state.collectAsState()
    var selectedDestination by remember { mutableStateOf(AppDestination.LEARN) }
    var dictionaryOpen by remember { mutableStateOf(false) }
    var requestedExerciseId by remember { mutableStateOf<String?>(null) }
    var requestedLessonId by remember { mutableStateOf<String?>(null) }
    var exerciseHasUnsavedProgress by remember { mutableStateOf(false) }
    var exercisePlayerOpen by remember { mutableStateOf(false) }
    var lessonOpen by remember { mutableStateOf(false) }
    var pendingDestination by remember { mutableStateOf<AppDestination?>(null) }
    fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        Telemetry.event(
            name = "permission_result",
            attributes = mapOf(
                "permission" to "notifications",
                "result" to if (granted) "granted" else "denied"
            )
        )
        if (granted && selectedDestination == AppDestination.LEARN) {
            conversationController.startConversation()
        } else if (!granted) {
            conversationController.reportNotificationPermissionDenied()
        }
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        Telemetry.event(
            name = "permission_result",
            attributes = mapOf(
                "permission" to "microphone",
                "result" to if (granted) "granted" else "denied"
            )
        )
        if (granted && selectedDestination == AppDestination.LEARN) {
            if (hasNotificationPermission()) {
                conversationController.startConversation()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(
                    Manifest.permission.POST_NOTIFICATIONS
                )
            }
        } else if (!granted) {
            conversationController.reportMicrophonePermissionDenied()
        }
    }
    val navigationBarPadding =
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    if (!skipModelReadiness && !uiState.modelsReady) {
        ModelReadinessScreen(
            downloads = modelDownloads,
            statusMessage = uiState.statusMessage
                ?: "Chargement des modèles sélectionnés en mémoire…",
            errorMessage = uiState.modelReadinessError,
            onRetry = {
                Telemetry.event("model_preload_retried")
                conversationController.preloadSelectedModel()
            },
            onOpenSettings = ::openModelSettings
        )
        return
    }

    LaunchedEffect(selectedDestination) {
        Telemetry.event(
            name = "screen_viewed",
            attributes = mapOf("screen" to selectedDestination.name.lowercase())
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (selectedDestination) {
            AppDestination.LEARN -> FreeModeScreen(
                uiState = uiState,
                animationsEnabled = animationsEnabled,
                onOpenSettings = ::openModelSettings,
                onSendText = conversationController::sendTextMessage,
                onDismissCreatedContent = {
                    conversationController.dismissCreatedContent()
                },
                onOpenCreatedContent = { content ->
                    Telemetry.event(
                        name = "created_content_opened",
                        attributes = mapOf(
                            "content_kind" to content.kind.name.lowercase()
                        )
                    )
                    conversationController.dismissCreatedContent()
                    conversationController.stopConversation()
                    when (content.kind) {
                        CreatedLearningContentKind.EXERCISE -> {
                            requestedExerciseId = content.id
                            selectedDestination = AppDestination.EXERCISES
                        }
                        CreatedLearningContentKind.LESSON -> {
                            requestedLessonId = content.id
                            selectedDestination = AppDestination.LESSONS
                        }
                    }
                },
                onPrimaryAction = {
                    if (uiState.isActive) {
                        conversationController.stopConversation()
                    } else if (
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        if (hasNotificationPermission()) {
                            conversationController.startConversation()
                        } else if (
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        ) {
                            notificationPermissionLauncher.launch(
                                Manifest.permission.POST_NOTIFICATIONS
                            )
                        }
                    } else {
                        microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            )
            AppDestination.EXERCISES -> ExercisesScreen(
                requestedOpenId = requestedExerciseId,
                onRequestedOpenHandled = { requestedExerciseId = null },
                exercises = learningContent.exercises.filter {
                    it.archivedAtMillis == null
                },
                onArchive = { exercise ->
                    learningContentRepository.archiveExercise(exercise.id)
                    Telemetry.event(
                        "learning_content_archived",
                        mapOf("content_kind" to "exercise")
                    )
                },
                onRemix = { exercise, guidance ->
                    conversationController.remixExercise(exercise, guidance)
                },
                onComplete = { exercise, mistakes, elapsedMillis, hintsUsed ->
                    learningContentRepository.completeExercise(
                        id = exercise.id,
                        mistakes = mistakes,
                        elapsedMillis = elapsedMillis,
                        hintsUsed = hintsUsed
                    )
                    Telemetry.event(
                        name = "exercise_completed",
                        attributes = mapOf(
                            "mistakes" to mistakes,
                            "elapsed_ms" to elapsedMillis,
                            "hints_used" to hintsUsed,
                            "language" to exercise.languageTag
                        )
                    )
                },
                onRateDifficulty = { exercise, rating ->
                    learningContentRepository.rateExercise(exercise.id, rating)
                    Telemetry.event(
                        name = "exercise_difficulty_rated",
                        attributes = mapOf("rating" to rating)
                    )
                },
                onSpeakWord = { text, languageTag ->
                    conversationController.speakPracticeWord(
                        text,
                        languageTag
                    )
                },
                onRecognizeAnswer = { languageTag ->
                    conversationController.recognizePracticeAnswer(
                        languageTag
                    )
                },
                onImportText = { sourceText ->
                    conversationController.importExerciseFromText(sourceText)
                },
                onImportYoutube = { videoUrl ->
                    conversationController.importExerciseFromYoutube(videoUrl)
                },
                onExerciseProgressChanged = { exerciseHasUnsavedProgress = it },
                onExerciseOpenChanged = { isOpen ->
                    if (exercisePlayerOpen != isOpen) {
                        Telemetry.event(
                            if (isOpen) "exercise_opened" else "exercise_closed"
                        )
                    }
                    exercisePlayerOpen = isOpen
                }
            )
            AppDestination.LESSONS -> LessonsScreen(
                requestedOpenId = requestedLessonId,
                onRequestedOpenHandled = { requestedLessonId = null },
                lessons = learningContent.lessons.filter {
                    it.archivedAtMillis == null
                },
                onArchive = { lesson ->
                    learningContentRepository.archiveLesson(lesson.id)
                    Telemetry.event(
                        "learning_content_archived",
                        mapOf("content_kind" to "lesson")
                    )
                },
                onRemix = { lesson, guidance ->
                    conversationController.remixLesson(lesson, guidance)
                },
                onAskQuestion = { lesson, question, history, onPreparingModel ->
                    conversationController.answerLessonQuestion(
                        lesson = lesson,
                        question = question,
                        conversationHistory = history,
                        onPreparingModel = onPreparingModel
                    )
                },
                onLessonOpenChanged = { isOpen ->
                    if (lessonOpen != isOpen) {
                        Telemetry.event(
                            if (isOpen) "lesson_opened" else "lesson_closed"
                        )
                    }
                    lessonOpen = isOpen
                }
            )
            AppDestination.PROFILE -> ProfileScreen(
                dictionaryOpen = dictionaryOpen,
                onOpenDictionary = {
                    Telemetry.event("dictionary_opened")
                    dictionaryOpen = true
                },
                onCloseDictionary = {
                    Telemetry.event("dictionary_closed")
                    dictionaryOpen = false
                },
                exercises = learningContent.exercises.filter {
                    it.archivedAtMillis == null
                },
                onOpenExercise = { exerciseId ->
                    dictionaryOpen = false
                    requestedExerciseId = exerciseId
                    selectedDestination = AppDestination.EXERCISES
                }
            )
        }

        if (!exercisePlayerOpen) ExpressiveNavigationBar(
            selectedDestination = selectedDestination,
            onDestinationSelected = { destination ->
                Telemetry.event(
                    name = "navigation_selected",
                    attributes = mapOf(
                        "from" to selectedDestination.name.lowercase(),
                        "to" to destination.name.lowercase()
                    )
                )
                if (
                    selectedDestination == AppDestination.EXERCISES &&
                    exerciseHasUnsavedProgress &&
                    destination != AppDestination.EXERCISES
                ) {
                    pendingDestination = destination
                } else {
                    selectedDestination = destination
                    if (destination != AppDestination.PROFILE) {
                        dictionaryOpen = false
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = 14.dp,
                    end = 14.dp,
                    bottom = navigationBarPadding + 10.dp
                )
        )

        pendingDestination?.let { destination ->
            UnsavedExerciseExitDialog(
                onDismiss = {
                    Telemetry.event("unsaved_exercise_exit_cancelled")
                    pendingDestination = null
                },
                onConfirm = {
                    Telemetry.event("unsaved_exercise_exit_confirmed")
                    pendingDestination = null
                    exerciseHasUnsavedProgress = false
                    selectedDestination = destination
                    if (destination != AppDestination.PROFILE) {
                        dictionaryOpen = false
                    }
                }
            )
        }
    }
}
