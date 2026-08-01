package com.anis.larp.ui.freemode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.anis.larp.ui.components.ExpressivePill
import com.anis.larp.ui.components.VoiceOrb
import kotlin.math.min

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FreeModeScreen(
    uiState: FreeModeUiState,
    onPrimaryAction: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    onDismissCreatedContent: () -> Unit = {},
    onOpenCreatedContent: (CreatedLearningContent) -> Unit = {},
    animationsEnabled: Boolean = true
) {
    uiState.createdContent?.let { content ->
        CreatedContentBottomSheet(
            content = content,
            onDismiss = onDismissCreatedContent,
            onOpen = { onOpenCreatedContent(content) }
        )
    }
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val compactHeight = maxHeight < 720.dp
            val orbSize = min(
                (maxWidth - 80.dp).value,
                if (compactHeight) 176f else 224f
            ).dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = 20.dp,
                        top = statusBarPadding + 12.dp,
                        end = 20.dp,
                        bottom = 112.dp
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                FreeModeTopArea(
                    localeLabel = uiState.locale.displayLanguageLabel(),
                    onOpenSettings = onOpenSettings
                )
                Spacer(Modifier.height(if (compactHeight) 24.dp else 42.dp))
                VoiceOrb(
                    isActive = uiState.isActive,
                    size = orbSize,
                    animateIdle = animationsEnabled,
                    accessibilityDescription = uiState.orbDescription()
                )
                Spacer(Modifier.height(18.dp))
                if (uiState.phase == SpeechPhase.THINKING) {
                    Row(
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LoadingIndicator(modifier = Modifier.size(32.dp))
                        Text(
                            text = uiState.thinkingWord ?: "Thinking",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    Text(
                        text = uiState.statusTitle(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = uiState.statusDescription(),
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.height(18.dp))
                TranscriptSurface(uiState = uiState)
                Spacer(Modifier.height(if (compactHeight) 18.dp else 24.dp))
                ExpressivePill(
                    label = if (uiState.isActive) {
                        "Terminer la conversation"
                    } else {
                        "Commencer à parler"
                    },
                    onClick = onPrimaryAction,
                    modifier = Modifier.testTag("primary_speech_action")
                )
                Spacer(Modifier.height(22.dp))
                SupportingActions()
            }
        }
    }
}

@Composable
private fun FreeModeTopArea(
    localeLabel: String,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "larp",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Libre",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.tertiaryContainer
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Language,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = localeLabel,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
        FilledTonalIconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(48.dp)
                .testTag("open_model_settings")
        ) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = "Ouvrir les paramètres des modèles"
            )
        }
    }
}

@Composable
private fun TranscriptSurface(uiState: FreeModeUiState) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("live_transcript"),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Transcription",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            if (uiState.committedTranscript.isNotBlank()) {
                Text(
                    text = "Vous",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = uiState.committedTranscript,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (uiState.aiReply.isNotBlank()) {
                Text(
                    text = buildString {
                        append("larp")
                        uiState.replyLocale?.let {
                            append(" · ")
                            append(it.toLanguageTag())
                        }
                        uiState.promptModelName
                            ?.takeIf(String::isNotBlank)
                            ?.let {
                                append(" · ")
                                append(it)
                            }
                    },
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = uiState.aiReply,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            when {
                uiState.partialTranscript.isNotBlank() -> Text(
                    text = uiState.partialTranscript,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = FontStyle.Italic
                )

                uiState.committedTranscript.isBlank() -> Text(
                    text = if (uiState.phase == SpeechPhase.LISTENING) {
                        "Parlez maintenant…"
                    } else {
                        "Votre transcription apparaîtra ici."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreatedContentBottomSheet(
    content: CreatedLearningContent,
    onDismiss: () -> Unit,
    onOpen: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (content.kind == CreatedLearningContentKind.EXERCISE) {
                    "Nouvel exercice"
                } else {
                    "Nouvelle leçon"
                },
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge
            )
            Text(content.title, style = MaterialTheme.typography.headlineSmall)
            if (content.difficulty != null || content.topic != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    content.difficulty?.let { difficulty ->
                        SuggestionChip(
                            onClick = {},
                            label = { Text(difficulty) },
                            modifier = Modifier.testTag("created_content_difficulty")
                        )
                    }
                    content.topic?.let { topic ->
                        SuggestionChip(
                            onClick = {},
                            label = { Text(topic) },
                            modifier = Modifier.testTag("created_content_topic")
                        )
                    }
                }
            }
            Text(
                text = content.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge
            )
            FilledTonalButton(
                onClick = onOpen,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag("open_created_content")
            ) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
                Text(
                    text = if (content.kind == CreatedLearningContentKind.EXERCISE) {
                        "Commencer l'exercice"
                    } else {
                        "Ouvrir la leçon"
                    },
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

private fun FreeModeUiState.statusTitle(): String = when (phase) {
    SpeechPhase.IDLE -> "Prêt à transcrire"
    SpeechPhase.PREPARING -> "Préparation"
    SpeechPhase.LISTENING -> "Écoute en cours"
    SpeechPhase.THINKING -> "Réflexion sur l'appareil"
    SpeechPhase.SPEAKING -> "larp répond"
    SpeechPhase.ERROR -> "Conversation indisponible"
}

private fun FreeModeUiState.statusDescription(): String = when (phase) {
    SpeechPhase.IDLE -> statusMessage
        ?: "Touchez le bouton pour utiliser le microphone"
    SpeechPhase.PREPARING -> statusMessage
        ?: "Initialisation du modèle vocal sur l'appareil"
    SpeechPhase.LISTENING -> buildString {
        append("Langue : ")
        append(locale.toLanguageTag())
        recognitionMode?.let { append(" · Mode $it") }
    }
    SpeechPhase.THINKING -> statusMessage
        ?: "${promptModelName ?: "Le modèle"} prépare une réponse…"
    SpeechPhase.SPEAKING -> statusMessage
        ?: "Synthèse vocale hors ligne"
    SpeechPhase.ERROR -> statusMessage
        ?: "Réessayez ou vérifiez l'autorisation du microphone"
}

private fun FreeModeUiState.orbDescription(): String = when (phase) {
    SpeechPhase.IDLE -> "Orbe vocal, prêt"
    SpeechPhase.PREPARING -> "Orbe vocal, préparation"
    SpeechPhase.LISTENING -> "Orbe vocal, microphone actif"
    SpeechPhase.THINKING ->
        "Orbe vocal, ${promptModelName ?: "Le modèle"} prépare une réponse"
    SpeechPhase.SPEAKING -> "Orbe vocal, larp parle"
    SpeechPhase.ERROR -> "Orbe vocal, conversation indisponible"
}

private fun java.util.Locale.displayLanguageLabel(): String {
    val label = getDisplayLanguage(this).ifBlank { toLanguageTag() }
    return label.replaceFirstChar { character ->
        if (character.isLowerCase()) character.titlecase(this) else character.toString()
    }
}

@Composable
private fun SupportingActions() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        FilledTonalButton(
            onClick = {},
            enabled = false,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .testTag("resume_action"),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Icon(
                imageVector = Icons.Rounded.History,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "Reprendre la dernière activité",
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        FilledTonalButton(
            onClick = {},
            enabled = false,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .testTag("write_action"),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "Écrire au lieu de parler",
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        Text(
            text = "Disponible dans une prochaine étape",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
