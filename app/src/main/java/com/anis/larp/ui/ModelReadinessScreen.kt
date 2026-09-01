package com.anis.larp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.anis.larp.model.ModelDownloadWorker
import com.anis.larp.ui.preview.LarpPhonePreviews
import com.anis.larp.ui.preview.LarpPreviewTheme

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun ModelReadinessScreen(
    downloads: List<WorkInfo>,
    statusMessage: String,
    errorMessage: String? = null,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeDownloads = downloads.filter { !it.state.isFinished }
    val knownProgress = activeDownloads.mapNotNull { work ->
        work.progress.getInt(ModelDownloadWorker.KEY_PROGRESS_PERCENT, 0)
            .takeIf { it > 0 }
    }.minOrNull()
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = 24.dp,
                    top = WindowInsets.statusBars.asPaddingValues()
                        .calculateTopPadding() + 28.dp,
                    end = 24.dp,
                    bottom = 28.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                modifier = Modifier.size(96.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    Icons.Rounded.Memory,
                    contentDescription = null,
                    modifier = Modifier.padding(26.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.height(26.dp))
            Text(
                text = if (errorMessage == null) "Préparation de l'IA" else "Modèles indisponibles",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Text(
                text = errorMessage ?: statusMessage,
                modifier = Modifier.padding(top = 10.dp),
                color = if (errorMessage == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            if (errorMessage == null) {
                if (knownProgress != null) {
                    LinearProgressIndicator(
                        progress = { knownProgress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "$knownProgress %",
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.labelLarge
                    )
                } else {
                    LoadingIndicator(modifier = Modifier.size(48.dp))
                }
                Text(
                    text = "Les fonctions IA s'ouvriront automatiquement quand les modèles sélectionnés seront prêts.",
                    modifier = Modifier.padding(top = 18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge
                ) {
                    Text("Réessayer")
                }
            }
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("open_model_settings")
                    .padding(top = 12.dp),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Icon(Icons.Rounded.Settings, contentDescription = null)
                Text("Changer de modèles", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@LarpPhonePreviews
@Composable
private fun ModelReadinessLoadingPreview() {
    LarpPreviewTheme {
        ModelReadinessScreen(
            downloads = emptyList(),
            statusMessage = "Chargement de Gemma 4 sur le NPU…",
            onRetry = {},
            onOpenSettings = {}
        )
    }
}

@LarpPhonePreviews
@Composable
private fun ModelReadinessErrorPreview() {
    LarpPreviewTheme {
        ModelReadinessScreen(
            downloads = emptyList(),
            statusMessage = "Préparation des modèles…",
            errorMessage = "Le modèle sélectionné n'est pas disponible sur cet appareil.",
            onRetry = {},
            onOpenSettings = {}
        )
    }
}
