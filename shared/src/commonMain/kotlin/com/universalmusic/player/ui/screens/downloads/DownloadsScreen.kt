package com.universalmusic.player.ui.screens

import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import com.universalmusic.player.data.cache.TrackAvailability
import com.universalmusic.player.ui.components.AccentBadge
import com.universalmusic.player.ui.components.AvailabilityMark
import com.universalmusic.player.ui.components.SectionRule
import com.universalmusic.player.ui.components.SourceKind
import com.universalmusic.player.ui.components.SourceMark
import com.universalmusic.player.ui.theme.AccentSlot
import com.universalmusic.player.ui.theme.AccentUse
import com.universalmusic.player.ui.theme.Accents
import com.universalmusic.player.ui.theme.LocalKainosPalette
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.data.cache.DownloadPhase
import com.universalmusic.player.data.cache.DownloadItemState
import com.universalmusic.player.data.cache.DownloadsSnapshot
import com.universalmusic.player.platform.currentTimeMillis
import com.universalmusic.player.platform.platformLabel

@Composable
fun DownloadsSection(container: AppContainer) {
    val presenter = remember(container) { DownloadsPresenter.from(container) }
    val snapshot by presenter.state.collectAsState()
    val settings by container.settings.collectAsState()
    DownloadsContent(
        snapshot = snapshot,
        platform = platformLabel(),
        wifiOnly = settings.downloadOnWifiOnly,
        actions = DownloadActions(
            retry = presenter::retry,
            remove = presenter::remove,
            download = presenter::download,
            togglePin = presenter::togglePin,
            setWifiOnly = presenter::setWifiOnly,
        ),
    )
}

internal class DownloadActions(
    val retry: (String) -> Unit = {},
    val remove: (String) -> Unit = {},
    val download: (String) -> Unit = {},
    val togglePin: (String) -> Unit = {},
    val setWifiOnly: (Boolean) -> Unit = {},
)

@Composable
internal fun DownloadsContent(
    snapshot: DownloadsSnapshot,
    platform: String,
    wifiOnly: Boolean,
    actions: DownloadActions,
    now: Long = currentTimeMillis(),
) {
    val usedMiB = snapshot.bytesUsed / (1024 * 1024)
    val maxMiB = snapshot.maxBytes / (1024 * 1024)
    val android = platform == "Android"
    SectionRule("Downloads")
    Text(
        "Hearted YouTube audio and YouTube matches for Spotify hearts can be cached. " +
            "Local files play from your folders. Spotify DRM audio is never downloaded.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (android) {
        SettingToggle("Download on Wi-Fi only", wifiOnly, actions.setWifiOnly)
        Text(
            if (wifiOnly) {
                "On mobile data, downloads wait for Wi-Fi. Playback still streams."
            } else {
                "Downloads also run on mobile data."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    snapshot.extractorIssue?.let { ExtractorNotice(it, android) }
    Text(
        "Storage: $usedMiB / $maxMiB MiB, ${snapshot.entryCount} cached",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
    if (snapshot.maxBytes > 0) {
        LinearProgressIndicator(
            progress = { (snapshot.bytesUsed.toFloat() / snapshot.maxBytes.toFloat()).coerceIn(0f, 1f) },
            color = LocalKainosPalette.current.accent(Accents.cached, AccentUse.Mark),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
    if (snapshot.items.isEmpty()) {
        Text(
            "No downloads yet. Heart a YouTube track (or a Spotify track that can match YouTube) to cache audio.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    } else {
        snapshot.items.forEach { item -> DownloadRow(item, actions, now) }
    }
    if (snapshot.recentEvictions.isNotEmpty()) {
        SectionRule("Recent cache removals", Modifier.padding(top = 16.dp))
        Text(
            "Pinned and currently playing items are never removed to free space.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        snapshot.recentEvictions.take(8).forEach { eviction ->
            Text(
                "${eviction.ownerCanonicalId}, ${eviction.reason}, ${eviction.sizeBytes / 1024} KiB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Shown once for the whole list, not per row: the fix is the same for every failed download. */
@Composable
private fun ExtractorNotice(reason: String, android: Boolean) {
    val rule = LocalKainosPalette.current.accent(Accents.failed, AccentUse.Mark)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .drawBehind { drawLine(rule, Offset(0f, 0f), Offset(0f, size.height), 3.dp.toPx()) }
            .padding(start = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(extractorFix(android), style = MaterialTheme.typography.titleSmall)
        Text(
            "YouTube is refusing downloads in a way that usually means the extractor is out of date. " +
                "Failed downloads try again on the next launch.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun extractorFix(android: Boolean): String = if (android) {
    "Update Kainos Player to get a newer YouTube extractor."
} else {
    "Update yt-dlp: run scripts/install-yt-dlp.sh, then restart."
}

@Composable
private fun DownloadRow(item: DownloadItemState, actions: DownloadActions, now: Long) {
    val hair = LocalKainosPalette.current.hair
    val id = item.ownerCanonicalId
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawLine(hair, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(item.title, style = MaterialTheme.typography.titleSmall)
        Text(
            buildString {
                append(item.artistLine)
                item.sizeBytes?.let { append(", ${it / 1024} KiB") }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AvailabilityMark(item.phase.availability, phaseLabel(item, now))
            if (item.isSpotifyViaYouTubeMatch) SourceMark(SourceKind.YouTube, "YouTube match")
            if (item.pinned) AccentBadge("Pinned", AccentSlot.Orange)
        }
        item.errorMessage?.let {
            // A retry is expected to recover, so only a final failure reads as an error.
            val color = if (item.phase == DownloadPhase.FAILED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(it, style = MaterialTheme.typography.bodySmall, color = color)
        }
        if (item.phase == DownloadPhase.DOWNLOADING) {
            val progress = item.progress
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    color = LocalKainosPalette.current.accent(Accents.downloading, AccentUse.Mark),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(
                    color = LocalKainosPalette.current.accent(Accents.downloading, AccentUse.Mark),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.phase == DownloadPhase.NOT_DOWNLOADED) {
                OutlinedButton(onClick = { actions.download(id) }) { Text("Download") }
                return@Row
            }
            if (item.phase == DownloadPhase.FAILED || item.retryAtMs != null) {
                TextButton(onClick = { actions.retry(id) }) { Text(if (item.retryAtMs != null) "Retry now" else "Retry") }
            }
            TextButton(onClick = { actions.togglePin(id) }) {
                Text(if (item.pinned) "Unpin" else "Pin")
            }
            OutlinedButton(onClick = { actions.remove(id) }) { Text("Remove") }
        }
    }
}

private val DownloadPhase.availability: TrackAvailability
    get() = when (this) {
        DownloadPhase.QUEUED, DownloadPhase.WAITING_FOR_NETWORK, DownloadPhase.WAITING_FOR_WIFI ->
            TrackAvailability.WAITING_FOR_NETWORK
        DownloadPhase.DOWNLOADING -> TrackAvailability.DOWNLOADING
        DownloadPhase.FAILED -> TrackAvailability.FAILED
        DownloadPhase.CACHED -> TrackAvailability.CACHED
        DownloadPhase.NOT_DOWNLOADED -> TrackAvailability.UNAVAILABLE
    }

internal fun phaseLabel(item: DownloadItemState, now: Long): String = when (item.phase) {
    DownloadPhase.QUEUED -> item.retryAtMs?.let { retryLabel(it - now) } ?: "Queued"
    DownloadPhase.WAITING_FOR_NETWORK -> "Waiting for network"
    DownloadPhase.WAITING_FOR_WIFI -> "Waiting for Wi-Fi"
    DownloadPhase.DOWNLOADING -> "Downloading"
    DownloadPhase.FAILED -> "Failed"
    DownloadPhase.CACHED -> "Cached"
    DownloadPhase.NOT_DOWNLOADED -> "Removed from this device"
}

private fun retryLabel(remainingMs: Long): String {
    val minutes = (remainingMs + 59_999) / 60_000
    return if (minutes <= 1) "Retrying in under a minute" else "Retrying in about $minutes min"
}
