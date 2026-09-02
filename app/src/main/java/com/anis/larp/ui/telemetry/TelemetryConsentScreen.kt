package com.anis.larp.ui.telemetry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.anis.larp.R

@Composable
fun TelemetryConsentScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier
) {
    val topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("telemetry_consent_screen"),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 24.dp, top = topPadding + 28.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.telemetry_consent_title),
                style = MaterialTheme.typography.headlineLarge
            )
            Text(
                text = stringResource(R.string.telemetry_consent_intro),
                modifier = Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(28.dp))
            ConsentPoint(
                icon = Icons.Rounded.QueryStats,
                title = stringResource(R.string.telemetry_consent_events_title),
                body = stringResource(R.string.telemetry_consent_events_body)
            )
            ConsentPoint(
                icon = Icons.Rounded.BugReport,
                title = stringResource(R.string.telemetry_consent_errors_title),
                body = stringResource(R.string.telemetry_consent_errors_body)
            )
            ConsentPoint(
                icon = Icons.Rounded.Lock,
                title = stringResource(R.string.telemetry_consent_private_title),
                body = stringResource(R.string.telemetry_consent_private_body)
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.telemetry_consent_change_later),
                modifier = Modifier.padding(bottom = 14.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            FilledTonalButton(
                onClick = onAccept,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("telemetry_consent_accept")
            ) {
                Text(stringResource(R.string.telemetry_consent_accept))
            }
            OutlinedButton(
                onClick = onDecline,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .testTag("telemetry_consent_decline")
            ) {
                Text(stringResource(R.string.telemetry_consent_decline))
            }
        }
    }
}

@Composable
private fun ConsentPoint(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                modifier = Modifier.padding(top = 3.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
