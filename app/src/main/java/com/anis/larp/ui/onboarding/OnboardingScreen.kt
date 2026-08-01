package com.anis.larp.ui.onboarding

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.anis.larp.model.DeviceAccelerationProfile
import com.anis.larp.model.LearningLanguage
import com.anis.larp.model.NativeLanguageChoice
import com.anis.larp.model.PromptModelCatalog
import com.anis.larp.model.PromptModelRecord
import com.anis.larp.model.commonNativeLanguages
import com.anis.larp.model.displayNameIn
import com.anis.larp.model.parseHuggingFaceModelReference
import com.anis.larp.ui.components.ExpressivePill
import java.util.Locale
import kotlinx.coroutines.launch

data class OnboardingSelection(
    val nativeLanguageTag: String,
    val targetLanguage: LearningLanguage,
    val promptSetup: PromptSetup
)

sealed interface PromptSetup {
    val modelId: String

    data object GeminiNano : PromptSetup {
        override val modelId = com.anis.larp.model.ModelPreferences.PROMPT_GEMINI_NANO
    }

    data class HuggingFace(
        val repository: String,
        val displayName: String,
        val isDefaultGemma4: Boolean,
        val requestedFileName: String? = null
    ) : PromptSetup {
        override val modelId: String =
            PromptModelCatalog.remoteModelId(repository, requestedFileName)
    }

    data class Imported(
        val record: PromptModelRecord
    ) : PromptSetup {
        override val modelId: String = record.id
    }
}

private enum class OnboardingStep {
    NATIVE_LANGUAGE,
    TARGET_LANGUAGE,
    PROMPT_MODEL
}

private enum class PromptChoice {
    GEMINI_NANO,
    GEMMA_4
}

private enum class AdvancedSource {
    HUGGING_FACE,
    FILE
}

@Composable
fun OnboardingScreen(
    promptCatalog: PromptModelCatalog,
    onComplete: (OnboardingSelection) -> Unit,
    modifier: Modifier = Modifier
) {
    val deviceLocale = remember { Locale.getDefault() }
    val accelerationProfile = remember { DeviceAccelerationProfile.detect() }
    val sharedGemmaAvailable = remember(promptCatalog) {
        promptCatalog.find(
            PromptModelCatalog.remoteModelId(
                PromptModelCatalog.GEMMA_4_REPOSITORY,
                accelerationProfile.gemmaArtifactFileName
            )
        ) != null
    }
    val coroutineScope = rememberCoroutineScope()
    var step by remember { mutableStateOf(OnboardingStep.NATIVE_LANGUAGE) }
    var nativeLanguageTag by remember {
        mutableStateOf(deviceLocale.toLanguageTag())
    }
    var correctingNativeLanguage by remember { mutableStateOf(false) }
    var targetLanguage by remember { mutableStateOf<LearningLanguage?>(null) }
    var promptChoice by remember { mutableStateOf<PromptChoice?>(null) }
    var advancedExpanded by remember { mutableStateOf(false) }
    var advancedSource by remember {
        mutableStateOf(AdvancedSource.HUGGING_FACE)
    }
    var customRepository by remember { mutableStateOf("") }
    var importedModel by remember { mutableStateOf<PromptModelRecord?>(null) }
    var importingFile by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var pendingCompletion by remember { mutableStateOf<OnboardingSelection?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        pendingCompletion?.let(onComplete)
        pendingCompletion = null
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            importingFile = true
            importError = null
            coroutineScope.launch {
                runCatching { promptCatalog.importModel(uri) }
                    .onSuccess { importedModel = it }
                    .onFailure {
                        importError = it.message ?: "Import du modèle impossible."
                    }
                importingFile = false
            }
        }
    }
    val statusBarPadding =
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    fun finish(selection: OnboardingSelection) {
        val needsDownload = selection.promptSetup is PromptSetup.HuggingFace &&
            promptCatalog.find(selection.promptSetup.modelId) == null
        if (
            needsDownload &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            pendingCompletion = selection
            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            onComplete(selection)
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 20.dp,
                    top = statusBarPadding + 12.dp,
                    end = 20.dp,
                    bottom = 24.dp
                )
                .animateContentSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OnboardingHeader(
                step = step,
                onBack = when (step) {
                    OnboardingStep.NATIVE_LANGUAGE -> null
                    OnboardingStep.TARGET_LANGUAGE -> {
                        { step = OnboardingStep.NATIVE_LANGUAGE }
                    }
                    OnboardingStep.PROMPT_MODEL -> {
                        { step = OnboardingStep.TARGET_LANGUAGE }
                    }
                }
            )
            Spacer(Modifier.height(30.dp))

            when (step) {
                OnboardingStep.NATIVE_LANGUAGE -> NativeLanguageStep(
                    deviceLocale = deviceLocale,
                    selectedLanguageTag = nativeLanguageTag,
                    correcting = correctingNativeLanguage,
                    onSelected = { nativeLanguageTag = it },
                    onCorrect = { correctingNativeLanguage = true },
                    onConfirm = { step = OnboardingStep.TARGET_LANGUAGE }
                )

                OnboardingStep.TARGET_LANGUAGE -> TargetLanguageStep(
                    selected = targetLanguage,
                    onSelected = { targetLanguage = it },
                    onContinue = { step = OnboardingStep.PROMPT_MODEL }
                )

                OnboardingStep.PROMPT_MODEL -> PromptModelStep(
                    selected = promptChoice,
                    onSelected = { promptChoice = it },
                    advancedExpanded = advancedExpanded,
                    onAdvancedExpandedChange = { advancedExpanded = it },
                    advancedSource = advancedSource,
                    onAdvancedSourceSelected = { advancedSource = it },
                    customRepository = customRepository,
                    onCustomRepositoryChanged = { customRepository = it },
                    importedModel = importedModel,
                    importingFile = importingFile,
                    importError = importError,
                    onPickFile = {
                        filePicker.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                    accelerationLabel = accelerationProfile.label,
                    sharedGemmaAvailable = sharedGemmaAvailable,
                    speculativeDecoding =
                        accelerationProfile.preferredKind !=
                            com.anis.larp.model.AccelerationKind.NPU,
                    isArtifactSupported = accelerationProfile::supportsArtifact,
                    onContinue = {
                        val target = targetLanguage ?: return@PromptModelStep
                        val promptSetup = when (promptChoice) {
                            PromptChoice.GEMINI_NANO -> PromptSetup.GeminiNano
                            PromptChoice.GEMMA_4 -> when {
                                !advancedExpanded -> PromptSetup.HuggingFace(
                                    repository =
                                        PromptModelCatalog.GEMMA_4_REPOSITORY,
                                    displayName =
                                        PromptModelCatalog.GEMMA_4_DISPLAY_NAME,
                                    isDefaultGemma4 = true,
                                    requestedFileName =
                                        accelerationProfile.gemmaArtifactFileName
                                )

                                advancedSource == AdvancedSource.HUGGING_FACE ->
                                    parseHuggingFaceModelReference(customRepository)
                                        ?.let { reference ->
                                            PromptSetup.HuggingFace(
                                                repository = reference.repository,
                                                displayName = reference.requestedFileName
                                                    ?.substringAfterLast('/')
                                                    ?.substringBeforeLast(".litertlm")
                                                    ?: reference.repository.substringAfter('/'),
                                                isDefaultGemma4 = false,
                                                requestedFileName =
                                                    reference.requestedFileName
                                            )
                                        }

                                else -> importedModel?.let(PromptSetup::Imported)
                            }
                            null -> null
                        } ?: return@PromptModelStep
                        finish(
                            OnboardingSelection(
                                nativeLanguageTag = nativeLanguageTag,
                                targetLanguage = target,
                                promptSetup = promptSetup
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun OnboardingHeader(
    step: OnboardingStep,
    onBack: (() -> Unit)?
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                FilledTonalIconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Retour"
                    )
                }
            } else {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        Icons.Rounded.Language,
                        contentDescription = null,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(
                    text = "Bienvenue dans larp",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "Votre professeur de langues vocal",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OnboardingStep.entries.forEach { item ->
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(7.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = if (item.ordinal <= step.ordinal) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                ) {}
            }
        }
    }
}

@Composable
private fun NativeLanguageStep(
    deviceLocale: Locale,
    selectedLanguageTag: String,
    correcting: Boolean,
    onSelected: (String) -> Unit,
    onCorrect: () -> Unit,
    onConfirm: () -> Unit
) {
    StepTitle(
        icon = Icons.Rounded.Language,
        title = "Est-ce votre langue maternelle ?",
        subtitle = "Nous avons lu la langue actuellement utilisée par votre téléphone."
    )
    Spacer(Modifier.height(24.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = Locale.forLanguageTag(selectedLanguageTag)
                    .displayNameIn(deviceLocale),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
            Text(
                text = selectedLanguageTag,
                modifier = Modifier.padding(top = 5.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }

    if (correcting) {
        Spacer(Modifier.height(18.dp))
        Text(
            text = "Choisissez la bonne langue",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(8.dp))
        commonNativeLanguages(deviceLocale).forEach { language ->
            NativeLanguageRow(
                language = language,
                selected = language.languageTag == selectedLanguageTag,
                onSelected = { onSelected(language.languageTag) }
            )
        }
        Spacer(Modifier.height(18.dp))
        ExpressivePill(
            label = "Utiliser cette langue",
            onClick = onConfirm
        )
    } else {
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onCorrect,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 60.dp),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Text("Corriger")
            }
            Button(
                onClick = onConfirm,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 60.dp)
                    .testTag("confirm_native_language"),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Text("Confirmer")
            }
        }
    }
}

@Composable
private fun NativeLanguageRow(
    language: NativeLanguageChoice,
    selected: Boolean,
    onSelected: () -> Unit
) {
    SelectionCard(
        selected = selected,
        onClick = onSelected,
        title = language.displayName,
        description = language.languageTag
    )
}

@Composable
private fun TargetLanguageStep(
    selected: LearningLanguage?,
    onSelected: (LearningLanguage) -> Unit,
    onContinue: () -> Unit
) {
    StepTitle(
        icon = Icons.Rounded.AutoAwesome,
        title = "Quelle langue voulez-vous apprendre ?",
        subtitle = "larp adaptera l'écoute, la voix et les réponses à cette langue."
    )
    Spacer(Modifier.height(22.dp))
    LearningLanguage.entries.forEach { language ->
        SelectionCard(
            selected = selected == language,
            onClick = { onSelected(language) },
            title = language.displayName,
            description = language.nativeName
        )
    }
    Spacer(Modifier.height(20.dp))
    ExpressivePill(
        label = "Continuer",
        onClick = onContinue,
        enabled = selected != null
    )
}

@Composable
private fun PromptModelStep(
    selected: PromptChoice?,
    onSelected: (PromptChoice) -> Unit,
    advancedExpanded: Boolean,
    onAdvancedExpandedChange: (Boolean) -> Unit,
    advancedSource: AdvancedSource,
    onAdvancedSourceSelected: (AdvancedSource) -> Unit,
    customRepository: String,
    onCustomRepositoryChanged: (String) -> Unit,
    importedModel: PromptModelRecord?,
    importingFile: Boolean,
    importError: String?,
    onPickFile: () -> Unit,
    accelerationLabel: String,
    sharedGemmaAvailable: Boolean,
    speculativeDecoding: Boolean,
    isArtifactSupported: (String?) -> Boolean,
    onContinue: () -> Unit
) {
    StepTitle(
        icon = Icons.Rounded.Memory,
        title = "Quel professeur doit répondre ?",
        subtitle = "Vous pourrez changer chaque modèle plus tard dans les paramètres."
    )
    Spacer(Modifier.height(22.dp))
    SelectionCard(
        selected = selected == PromptChoice.GEMINI_NANO,
        onClick = { onSelected(PromptChoice.GEMINI_NANO) },
        title = "Gemini Nano",
        description = "Modèle Android AI Core, lorsqu'il est disponible sur ce téléphone"
    )
    SelectionCard(
        selected = selected == PromptChoice.GEMMA_4,
        onClick = { onSelected(PromptChoice.GEMMA_4) },
        title = "Gemma 4",
        description =
            buildString {
                if (sharedGemmaAvailable) {
                    append("Déjà présent dans Download/Models · ")
                } else {
                    append("Hugging Face · Download/Models · 2,6 à 3,1 Go · ")
                }
                append(accelerationLabel)
                if (speculativeDecoding) {
                    append(" · décodage spéculatif MTP")
                } else {
                    append(" · modèle NPU optimisé")
                }
            }
    )

    if (selected == PromptChoice.GEMMA_4) {
        TextButton(
            onClick = { onAdvancedExpandedChange(!advancedExpanded) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (advancedExpanded) "Masquer les options avancées" else "Options avancées")
        }
        if (advancedExpanded) {
            AdvancedPromptSource(
                selected = advancedSource,
                onSelected = onAdvancedSourceSelected,
                customRepository = customRepository,
                onCustomRepositoryChanged = onCustomRepositoryChanged,
                importedModel = importedModel,
                importingFile = importingFile,
                importError = importError,
                isArtifactSupported = isArtifactSupported,
                onPickFile = onPickFile
            )
        } else {
            DownloadNotificationCard(alreadyAvailable = sharedGemmaAvailable)
        }
    }

    Spacer(Modifier.height(20.dp))
    val parsedCustomReference = parseHuggingFaceModelReference(customRepository)
    val customRepositoryValid = parsedCustomReference != null &&
        isArtifactSupported(parsedCustomReference.requestedFileName)
    val importedModelValid = importedModel != null &&
        isArtifactSupported(importedModel.artifactFileName)
    val canContinue = when (selected) {
        PromptChoice.GEMINI_NANO -> true
        PromptChoice.GEMMA_4 -> when {
            !advancedExpanded -> true
            advancedSource == AdvancedSource.HUGGING_FACE -> customRepositoryValid
            else -> importedModelValid && !importingFile
        }
        null -> false
    }
    Button(
        onClick = onContinue,
        enabled = canContinue,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .testTag("finish_onboarding"),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        val usingSharedGemma = selected == PromptChoice.GEMMA_4 &&
            !advancedExpanded &&
            sharedGemmaAvailable
        Icon(
            imageVector = if (usingSharedGemma) {
                Icons.Rounded.FolderOpen
            } else if (
                selected == PromptChoice.GEMMA_4 &&
                (!advancedExpanded || advancedSource == AdvancedSource.HUGGING_FACE)
            ) {
                Icons.Rounded.CloudDownload
            } else {
                Icons.Rounded.AutoAwesome
            },
            contentDescription = null,
            modifier = Modifier.size(21.dp)
        )
        Text(
            text = if (usingSharedGemma) {
                "Utiliser et commencer"
            } else if (
                selected == PromptChoice.GEMMA_4 &&
                (!advancedExpanded || advancedSource == AdvancedSource.HUGGING_FACE)
            ) {
                "Télécharger et commencer"
            } else {
                "Commencer"
            },
            modifier = Modifier.padding(start = 9.dp)
        )
    }
}

@Composable
private fun AdvancedPromptSource(
    selected: AdvancedSource,
    onSelected: (AdvancedSource) -> Unit,
    customRepository: String,
    onCustomRepositoryChanged: (String) -> Unit,
    importedModel: PromptModelRecord?,
    importingFile: Boolean,
    importError: String?,
    isArtifactSupported: (String?) -> Boolean,
    onPickFile: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Modèle de référence personnalisé",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(10.dp))
            AdvancedRadioRow(
                selected = selected == AdvancedSource.HUGGING_FACE,
                title = "Dépôt ou URL Hugging Face",
                onClick = { onSelected(AdvancedSource.HUGGING_FACE) }
            )
            AdvancedRadioRow(
                selected = selected == AdvancedSource.FILE,
                title = "Fichier présent dans Files",
                onClick = { onSelected(AdvancedSource.FILE) }
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

            if (selected == AdvancedSource.HUGGING_FACE) {
                val parsedReference =
                    parseHuggingFaceModelReference(customRepository)
                val artifactSupported =
                    isArtifactSupported(parsedReference?.requestedFileName)
                OutlinedTextField(
                    value = customRepository,
                    onValueChange = onCustomRepositoryChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Dépôt ou URL Hugging Face") },
                    placeholder = {
                        Text("organisation/modèle ou huggingface.co/…/blob/main/…")
                    },
                    singleLine = true,
                    isError = parsedReference != null && !artifactSupported,
                    supportingText = {
                        Text(
                            when {
                                parsedReference?.requestedFileName != null &&
                                    !artifactSupported ->
                                    "Cet artefact est compilé pour un autre SoC et ferait planter LiteRT."
                                parsedReference?.requestedFileName != null ->
                                    "Fichier exact détecté : ${parsedReference.requestedFileName}"
                                else ->
                                    "Utilisez organisation/dépôt ou l'URL d'un fichier .litertlm."
                            }
                        )
                    }
                )
                DownloadNotificationCard()
            } else {
                FilledTonalButton(
                    onClick = onPickFile,
                    enabled = !importingFile,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge
                ) {
                    if (importingFile) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                    }
                    Text(
                        text = if (importingFile) {
                            "Import en cours…"
                        } else {
                            "Choisir dans Files"
                        },
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                importedModel?.let {
                    Text(
                        text = if (isArtifactSupported(it.artifactFileName)) {
                            "Référence directe : ${it.displayName}"
                        } else {
                            "Ce fichier cible un autre SoC et ne peut pas être utilisé ici."
                        },
                        modifier = Modifier.padding(top = 10.dp),
                        color = if (isArtifactSupported(it.artifactFileName)) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
                importError?.let {
                    Text(
                        text = it,
                        modifier = Modifier.padding(top = 10.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun AdvancedRadioRow(
    selected: Boolean,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text = title, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun DownloadNotificationCard(alreadyAvailable: Boolean = false) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.FolderOpen, contentDescription = null)
            Column {
                Text(
                    text = if (alreadyAvailable) {
                        "Gemma sera réutilisé depuis Download/Models"
                    } else {
                        "Conservé dans Download/Models"
                    },
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = if (alreadyAvailable) {
                        "Aucun nouveau téléchargement n'est nécessaire, même après avoir " +
                            "effacé les données de larp."
                    } else {
                        "Le fichier reste visible dans Files, utilisable par d'autres apps " +
                            "et conservé si les données de larp sont effacées. Android affichera " +
                            "la progression dans une notification."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun SelectionCard(
    selected: Boolean,
    onClick: () -> Unit,
    title: String,
    description: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        tonalElevation = if (selected) 3.dp else 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 6.dp)
            ) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun StepTitle(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Surface(
        modifier = Modifier.size(76.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.tertiaryContainer
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.padding(22.dp)
        )
    }
    Spacer(Modifier.height(18.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center
    )
    Text(
        text = subtitle,
        modifier = Modifier.padding(top = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center
    )
}
