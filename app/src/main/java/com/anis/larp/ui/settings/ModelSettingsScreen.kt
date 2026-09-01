package com.anis.larp.ui.settings

import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.anis.larp.model.DeviceAccelerationProfile
import com.anis.larp.model.InstalledModelCatalog
import com.anis.larp.model.InstalledModelOption
import com.anis.larp.model.LearningLanguage
import com.anis.larp.model.ModelDownloadManager
import com.anis.larp.model.ModelDownloadWorker
import com.anis.larp.model.ModelInventory
import com.anis.larp.model.ModelPreferences
import com.anis.larp.model.PromptModelCatalog
import com.anis.larp.model.QwenAsrModel
import com.anis.larp.model.displayNameIn
import com.anis.larp.ui.freemode.FreeModeSessionStore
import com.anis.larp.ui.preview.LarpPhonePreviews
import com.anis.larp.ui.preview.LarpPreviewData
import com.anis.larp.ui.preview.LarpPreviewTheme
import com.anis.larp.ui.preview.LarpTabletPreview
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun ModelSettingsScreen(
    preferences: ModelPreferences,
    promptCatalog: PromptModelCatalog,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val statusBarPadding =
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val installedCatalog = remember(context, promptCatalog) {
        InstalledModelCatalog(context, promptCatalog)
    }
    val downloadFlow = remember(context) {
        WorkManager.getInstance(context)
            .getWorkInfosByTagFlow(ModelDownloadManager.DOWNLOAD_TAG)
    }
    val downloads by downloadFlow.collectAsState(initial = emptyList())
    val sessionStore = remember(context.applicationContext) {
        FreeModeSessionStore.getInstance(context.applicationContext)
    }
    var refreshKey by remember { mutableIntStateOf(0) }
    var inventory by remember { mutableStateOf<ModelInventory?>(null) }
    var loadingError by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var importingPromptModel by remember { mutableStateOf(false) }
    var qwenImportExpanded by remember { mutableStateOf(false) }
    var importingQwenFiles by remember { mutableStateOf(false) }
    var qwenImportError by remember { mutableStateOf<String?>(null) }
    var currentTargetLanguage by remember {
        mutableStateOf(preferences.targetLanguage)
    }
    val coroutineScope = rememberCoroutineScope()
    val promptModelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            importingPromptModel = true
            importError = null
            coroutineScope.launch {
                runCatching { promptCatalog.importModel(uri) }
                    .onSuccess { record ->
                        val profile = DeviceAccelerationProfile.detect()
                        if (!profile.supportsArtifact(record.artifactFileName)) {
                            importError =
                                "Ce modèle cible un autre SoC et ferait planter LiteRT sur " +
                                    "cet appareil."
                        } else {
                            preferences.promptModelId = record.id
                            refreshKey++
                        }
                    }
                    .onFailure { error ->
                        importError = error.message ?: "Import du modèle impossible."
                    }
                importingPromptModel = false
            }
        }
    }
    val qwenFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            importingQwenFiles = true
            qwenImportError = null
            coroutineScope.launch {
                runCatching {
                    QwenAsrModel.importArtifacts(context.applicationContext, uris)
                }.onSuccess {
                    ModelDownloadManager(context.applicationContext)
                        .cancelQwenAsrDownloads()
                    preferences.sttModelId = ModelPreferences.STT_QWEN_3_ASR
                    refreshKey++
                }.onFailure { error ->
                    qwenImportError = error.message ?: "Import des fichiers Qwen impossible."
                }
                importingQwenFiles = false
            }
        }
    }
    val ttsDataInstaller = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshKey++
    }

    LaunchedEffect(
        refreshKey,
        currentTargetLanguage,
        downloads.map { it.state }
    ) {
        inventory = null
        loadingError = null
        runCatching {
            installedCatalog.load(
                targetLanguage = currentTargetLanguage,
                nativeLanguageTag = preferences.nativeLanguageTag
            )
        }.onSuccess {
            inventory = it
        }.onFailure {
            loadingError = it.message ?: "Inventaire des modèles indisponible."
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
                    bottom = 40.dp
                ),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                FilledTonalIconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("model_settings_back")
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Retour"
                    )
                }
                Column {
                    Text(
                        text = "Réglages",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        text = "Langue, voix, conversation et écoute",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            LearningLanguageCard(
                selected = currentTargetLanguage,
                onSelected = { language ->
                    currentTargetLanguage = language
                    preferences.targetLanguage = language
                    preferences.ttsVoiceName = null
                    refreshKey++
                }
            )
            AccelerationCard()
            ActiveDownloads(downloads)
            SessionHistoryCard(
                sessionCount = sessionStore.sessionFiles().size,
                hasActiveSession = sessionStore.activeSessionId() != null
            )

            when {
                inventory != null -> {
                    val models = requireNotNull(inventory)
                    ModelSection(
                        title = "TTS · Voix",
                        description = "Voix hors ligne installées pour ${currentTargetLanguage.displayName}",
                        icon = Icons.Rounded.RecordVoiceOver,
                        options = models.ttsModels,
                        selectedId = preferences.ttsVoiceName,
                        emptyMessage = "Aucune voix hors ligne compatible n'est installée.",
                        onSelected = {
                            preferences.ttsVoiceName = it
                            refreshKey++
                        },
                        emptyActionLabel = "Installer une voix hors ligne",
                        onEmptyAction = {
                            val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                            if (intent.resolveActivity(context.packageManager) != null) {
                                ttsDataInstaller.launch(intent)
                            } else {
                                loadingError =
                                    "Le moteur vocal installé ne propose pas d'écran d'installation."
                            }
                        }
                    )
                    ImportPromptModelCard(
                        importing = importingPromptModel,
                        error = importError,
                        onImport = {
                            promptModelPicker.launch(
                                arrayOf("application/octet-stream", "*/*")
                            )
                        }
                    )
                    ModelSection(
                        title = "Prompt · Professeur",
                        description = "Le modèle réellement utilisé pour répondre",
                        icon = Icons.Rounded.Memory,
                        options = models.promptModels,
                        selectedId = preferences.promptModelId,
                        emptyMessage = "Aucun modèle de prompt n'est prêt.",
                        onSelected = {
                            preferences.promptModelId = it
                            refreshKey++
                        }
                    )
                    ModelSection(
                        title = "STT · Écoute",
                        description = "Reconnaissance installée pour ${Locale.forLanguageTag(preferences.nativeLanguageTag).displayNameIn()}",
                        icon = Icons.Rounded.GraphicEq,
                        options = models.sttModels,
                        selectedId = preferences.sttModelId,
                        emptyMessage = "Aucun modèle STT n'est déjà disponible pour cette langue.",
                        onSelected = {
                            preferences.sttModelId = it
                            refreshKey++
                        }
                    )
                    TextButton(
                        onClick = { qwenImportExpanded = !qwenImportExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (qwenImportExpanded) {
                                "Masquer l'import ASR avancé"
                            } else {
                                "Options ASR avancées"
                            }
                        )
                    }
                    if (qwenImportExpanded) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.extraLarge,
                            color = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text(
                                    text = "Importer Qwen depuis Files",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Choisissez ensemble le modèle .gguf et son fichier mmproj. Larp les conservera dans Download/Models.",
                                    modifier = Modifier.padding(top = 6.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                FilledTonalButton(
                                    onClick = {
                                        qwenFilePicker.launch(
                                            arrayOf("application/octet-stream", "*/*")
                                        )
                                    },
                                    enabled = !importingQwenFiles,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 14.dp)
                                ) {
                                    if (importingQwenFiles) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                                    }
                                    Text(
                                        "Choisir les deux fichiers",
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                                qwenImportError?.let { error ->
                                    Text(
                                        text = error,
                                        modifier = Modifier.padding(top = 10.dp),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }

                loadingError != null -> Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = requireNotNull(loadingError),
                        modifier = Modifier.padding(18.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }

                else -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun LearningLanguageCard(
    selected: LearningLanguage,
    onSelected: (LearningLanguage) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("learning_language_settings"),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Language, contentDescription = null)
                Column {
                    Text(
                        text = "Langue étudiée",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Langue utilisée par défaut pour Libre et les créations",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LearningLanguage.entries.forEach { language ->
                    FilterChip(
                        selected = language == selected,
                        onClick = { onSelected(language) },
                        label = { Text(language.nativeName) },
                        modifier = Modifier.testTag(
                            "learning_language_${language.name.lowercase()}"
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportPromptModelCard(
    importing: Boolean,
    error: String?,
    onImport: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Modèle conservé dans Files",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Redonnez à Larp l'accès à un modèle de Download/Models après " +
                    "une réinstallation ou un effacement des données.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
            FilledTonalButton(
                onClick = onImport,
                enabled = !importing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (importing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                }
                Text(
                    text = if (importing) "Import en cours…" else "Choisir dans Files",
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun SessionHistoryCard(
    sessionCount: Int,
    hasActiveSession: Boolean
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("session_history_summary"),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.tertiaryContainer
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.History,
                contentDescription = null,
                modifier = Modifier.size(28.dp)
            )
            Column {
                Text(
                    text = "Historique Libre",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = buildString {
                        append(sessionCount)
                        append(if (sessionCount == 1) " session locale" else " sessions locales")
                        if (hasActiveSession) append(" · conversation en cours")
                        append(" · aucun audio enregistré")
                    },
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun AccelerationCard(previewLabel: String? = null) {
    val profileLabel = previewLabel ?: remember { DeviceAccelerationProfile.detect().label }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Bolt,
                contentDescription = null,
                modifier = Modifier.size(28.dp)
            )
            Column {
                Text(
                    text = "Accélération automatique",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = profileLabel,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun ActiveDownloads(downloads: List<WorkInfo>) {
    downloads
        .filter { !it.state.isFinished }
        .forEach { info ->
            val progress = info.progress.getInt(
                ModelDownloadWorker.KEY_PROGRESS_PERCENT,
                0
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Téléchargement du modèle",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = if (progress > 0) "$progress %" else "Préparation…",
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
}

@Composable
private fun ModelSection(
    title: String,
    description: String,
    icon: ImageVector,
    options: List<InstalledModelOption>,
    selectedId: String?,
    emptyMessage: String,
    onSelected: (String) -> Unit,
    emptyActionLabel: String? = null,
    onEmptyAction: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier.padding(
                    start = 18.dp,
                    top = 14.dp,
                    end = 18.dp,
                    bottom = 8.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (options.isEmpty()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = emptyMessage,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (emptyActionLabel != null && onEmptyAction != null) {
                        FilledTonalButton(
                            onClick = onEmptyAction,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(emptyActionLabel)
                        }
                    }
                }
            } else {
                options.forEachIndexed { index, option ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 18.dp)
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(option.id) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedId == option.id,
                            onClick = { onSelected(option.id) }
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 4.dp)
                        ) {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = option.description,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(2.dp))
}

@LarpPhonePreviews
@LarpTabletPreview
@Composable
private fun ModelSettingsScreenPreview() {
    val models = LarpPreviewData.modelInventory
    LarpPreviewTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    FilledTonalIconButton(onClick = {}) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Retour"
                        )
                    }
                    Column {
                        Text("Réglages", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "Langue, voix, conversation et écoute",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                LearningLanguageCard(
                    selected = LearningLanguage.SPANISH,
                    onSelected = {}
                )
                AccelerationCard(previewLabel = "NPU · accélération locale")
                SessionHistoryCard(sessionCount = 12, hasActiveSession = true)
                ModelSection(
                    title = "TTS · Voix",
                    description = "Voix hors ligne installées pour Espagnol",
                    icon = Icons.Rounded.RecordVoiceOver,
                    options = models.ttsModels,
                    selectedId = models.ttsModels.first().id,
                    emptyMessage = "Aucune voix hors ligne compatible n'est installée.",
                    onSelected = {}
                )
                ImportPromptModelCard(
                    importing = false,
                    error = null,
                    onImport = {}
                )
                ModelSection(
                    title = "Prompt · Professeur",
                    description = "Le modèle réellement utilisé pour répondre",
                    icon = Icons.Rounded.Memory,
                    options = models.promptModels,
                    selectedId = models.promptModels.first().id,
                    emptyMessage = "Aucun modèle de prompt n'est prêt.",
                    onSelected = {}
                )
                ModelSection(
                    title = "STT · Écoute",
                    description = "Reconnaissance installée pour Français",
                    icon = Icons.Rounded.GraphicEq,
                    options = models.sttModels,
                    selectedId = models.sttModels.first().id,
                    emptyMessage = "Aucun modèle STT n'est disponible.",
                    onSelected = {}
                )
            }
        }
    }
}
