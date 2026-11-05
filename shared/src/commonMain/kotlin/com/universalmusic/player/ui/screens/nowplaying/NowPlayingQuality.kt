package com.universalmusic.player.ui.screens

import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.ui.components.AccentBadge
import com.universalmusic.player.ui.components.SourceKind
import com.universalmusic.player.ui.components.SourceMark
import com.universalmusic.player.ui.theme.AccentSlot
import com.universalmusic.player.ui.theme.Accents
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
    qualityClass: QualityClass?,
    syncWarning: String?,
    hasDetails: Boolean,
    detailsExpanded: Boolean,
    onToggleDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            providerName?.let { name ->
                val provider = ProviderId.entries.firstOrNull { it.displayName == name }
                if (provider != null) SourceMark(SourceKind.of(provider), name, showIcon = true)
                else Text(name, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            }
            qualityLabel?.let { label ->
                AccentBadge(
                    label,
                    when (qualityClass) {
                        QualityClass.HiRes -> Accents.hiRes
                        QualityClass.Cd -> Accents.cdQuality
                        QualityClass.Lossy -> Accents.lossy
                        null -> AccentSlot.Gray
                    },
                )
            }
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
