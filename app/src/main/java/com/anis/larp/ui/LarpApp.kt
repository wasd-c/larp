package com.anis.larp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import com.anis.larp.model.ModelPreferences
import com.anis.larp.model.PromptModelCatalog
import com.anis.larp.model.DeviceAccelerationProfile
import com.anis.larp.model.QwenAsrModel
import com.anis.larp.ui.onboarding.OnboardingScreen
import com.anis.larp.ui.onboarding.PromptSetup
import com.anis.larp.ui.settings.ModelSettingsScreen

@Composable
fun LarpApp(
    animationsEnabled: Boolean = true,
    skipOnboarding: Boolean = false
) {
    val context = LocalContext.current
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

    LaunchedEffect(onboardingComplete) {
        val profile = DeviceAccelerationProfile.detect()
        val legacyGemmaModelId = PromptModelCatalog.remoteModelId(
            PromptModelCatalog.GEMMA_4_REPOSITORY
        )
        val gemmaModelId = PromptModelCatalog.remoteModelId(
            PromptModelCatalog.GEMMA_4_REPOSITORY,
            profile.gemmaArtifactFileName
        )
        if (
            onboardingComplete &&
            preferences.promptModelId in setOf(gemmaModelId, legacyGemmaModelId) &&
            promptCatalog.find(preferences.promptModelId) == null
        ) {
            downloadManager.enqueueGemma4()
        }
        if (
            onboardingComplete &&
            preferences.sttModelId == ModelPreferences.STT_QWEN_3_ASR &&
            !QwenAsrModel.isAvailable(context.applicationContext)
        ) {
            downloadManager.enqueueQwenAsr()
        }
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
                onboardingComplete = true
            }
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
    val modelDownloads by remember(context.applicationContext) {
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosByTagFlow(ModelDownloadManager.DOWNLOAD_TAG)
    }.collectAsState(initial = emptyList())
    var selectedDestination by remember { mutableStateOf(AppDestination.LEARN) }
    var dictionaryOpen by remember { mutableStateOf(false) }
    var modelSettingsOpen by remember { mutableStateOf(false) }
    var requestedExerciseId by remember { mutableStateOf<String?>(null) }
    var requestedLessonId by remember { mutableStateOf<String?>(null) }
    var exerciseHasUnsavedProgress by remember { mutableStateOf(false) }
    var exercisePlayerOpen by remember { mutableStateOf(false) }
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
        if (granted && selectedDestination == AppDestination.LEARN) {
            conversationController.startConversation()
        } else if (!granted) {
            conversationController.reportNotificationPermissionDenied()
        }
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
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

    LaunchedEffect(modelDownloads.map { it.id to it.state }) {
        if (modelDownloads.any { it.state == WorkInfo.State.SUCCEEDED }) {
            conversationController.preloadSelectedModel()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (modelSettingsOpen) {
            ModelSettingsScreen(
                preferences = preferences,
                promptCatalog = promptCatalog,
                onBack = {
                    modelSettingsOpen = false
                    conversationController.preloadSelectedModel()
                }
            )
            return@Box
        }

        when (selectedDestination) {
            AppDestination.LEARN -> FreeModeScreen(
                uiState = uiState,
                animationsEnabled = animationsEnabled,
                onOpenSettings = { modelSettingsOpen = true },
                onDismissCreatedContent = {
                    conversationController.dismissCreatedContent()
                },
                onOpenCreatedContent = { content ->
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
                },
                onRateDifficulty = { exercise, rating ->
                    learningContentRepository.rateExercise(exercise.id, rating)
                },
                onSpeakWord = conversationController::speakPracticeWord,
                onRecognizeAnswer = conversationController::recognizePracticeAnswer,
                onImportText = { sourceText ->
                    conversationController.importExerciseFromText(sourceText)
                },
                onImportYoutube = { videoUrl ->
                    conversationController.importExerciseFromYoutube(videoUrl)
                },
                onExerciseProgressChanged = { exerciseHasUnsavedProgress = it },
                onExerciseOpenChanged = { exercisePlayerOpen = it }
            )
            AppDestination.LESSONS -> LessonsScreen(
                requestedOpenId = requestedLessonId,
                onRequestedOpenHandled = { requestedLessonId = null },
                lessons = learningContent.lessons.filter {
                    it.archivedAtMillis == null
                },
                onArchive = { lesson ->
                    learningContentRepository.archiveLesson(lesson.id)
                },
                onRemix = { lesson, guidance ->
                    conversationController.remixLesson(lesson, guidance)
                }
            )
            AppDestination.PROFILE -> ProfileScreen(
                dictionaryOpen = dictionaryOpen,
                onOpenDictionary = { dictionaryOpen = true },
                onCloseDictionary = { dictionaryOpen = false },
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
                onDismiss = { pendingDestination = null },
                onConfirm = {
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
