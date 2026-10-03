package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.universalmusic.player.data.cache.TrackAvailability
import com.universalmusic.player.data.cache.TrackAvailabilityInfo

@Composable
internal fun ProviderQualityRow(
    providerName: String?,
    qualityLabel: String?,
    syncWarning: String?,
    hasDetails: Boolean,
    detailsExpanded: Boolean,
    onToggleDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val spine = listOfNotNull(providerName, qualityLabel).joinToString(" · ")
            if (spine.isNotEmpty()) Text(
                spine,
                style = MaterialTheme.typography.labelLarge,
                // Scheme accent, not the provider brand color: mint/red labels were unreadable on light schemes.
                color = scheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (hasDetails) {
                TextButton(onClick = onToggleDetails) {
                    Text(if (detailsExpanded) "Hide details" else "Details")
                }
            }
        }
        syncWarning?.let { warning ->
            Text(
                warning,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
internal fun NowPlayingErrorBlock(
    message: String,
    showDiagnostics: Boolean,
    hasFallbacks: Boolean,
    scheme: ColorScheme,
    onRetry: () -> Unit,
    onTryAnother: () -> Unit,
    onToggleDiagnostics: () -> Unit,
) {
    Text(
        message,
        color = scheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onRetry) { Text("Retry") }
        if (hasFallbacks) {
            TextButton(onClick = onTryAnother) { Text("Try another source") }
        }
        TextButton(onClick = onToggleDiagnostics) {
            Text(if (showDiagnostics) "Hide diagnostics" else "Diagnostics")
        }
    }
}

@Composable
internal fun NowPlayingAvailabilityLine(
    info: TrackAvailabilityInfo,
    scheme: ColorScheme,
    modifier: Modifier = Modifier,
) {
    Text(
        availabilityLine(info),
        style = MaterialTheme.typography.labelMedium,
        color = if (info.status == TrackAvailability.FAILED || info.status == TrackAvailability.UNAVAILABLE) {
            scheme.error
        } else {
            scheme.onSurfaceVariant
        },
        modifier = modifier,
    )
}

@Composable
internal fun NowPlayingDetailLines(
    lines: List<String>,
    scheme: ColorScheme,
) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    lines.forEach { line ->
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
