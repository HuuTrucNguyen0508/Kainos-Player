package com.universalmusic.player.ui.screens

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.data.cache.DownloadPhase
import com.universalmusic.player.data.cache.DownloadItemState

@Composable
fun DownloadsSection(container: AppContainer) {
    val snapshot by container.heartedAudio.snapshot.collectAsState()
    val usedMiB = snapshot.bytesUsed / (1024 * 1024)
    val maxMiB = snapshot.maxBytes / (1024 * 1024)
    Text("Downloads", style = MaterialTheme.typography.titleMedium)
    Text(
        "Hearted YouTube audio and YouTube matches for Spotify hearts can be cached. " +
            "Local files play from your folders. Spotify DRM audio is never downloaded.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "Storage: $usedMiB / $maxMiB MiB · ${snapshot.entryCount} cached",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
    if (snapshot.maxBytes > 0) {
        LinearProgressIndicator(
            progress = { (snapshot.bytesUsed.toFloat() / snapshot.maxBytes.toFloat()).coerceIn(0f, 1f) },
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
        snapshot.items.forEach { item ->
            DownloadRow(
                item = item,
                onRetry = { container.retryHeartedDownload(item.ownerCanonicalId) },
                onRemove = { container.removeHeartedDownload(item.ownerCanonicalId) },
                onPinToggle = {
                    container.setHeartedDownloadPinned(item.ownerCanonicalId, !item.pinned)
                },
            )
        }
    }
    if (snapshot.recentEvictions.isNotEmpty()) {
        Text(
            "Recent cache removals",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "Pinned and currently playing items are never removed to free space.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        snapshot.recentEvictions.take(8).forEach { eviction ->
            Text(
                "${eviction.ownerCanonicalId} · ${eviction.reason} · ${eviction.sizeBytes / 1024} KiB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItemState,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onPinToggle: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(item.title, style = MaterialTheme.typography.titleSmall)
        Text(
            buildString {
                append(item.artistLine)
                append(" · ")
                append(phaseLabel(item))
                if (item.isSpotifyViaYouTubeMatch) append(" · YouTube match")
                if (item.pinned) append(" · Pinned")
                item.sizeBytes?.let { append(" · ${it / 1024} KiB") }
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (item.phase == DownloadPhase.FAILED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        item.errorMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (item.phase == DownloadPhase.DOWNLOADING) {
            val progress = item.progress
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.phase == DownloadPhase.FAILED || item.phase == DownloadPhase.WAITING_FOR_NETWORK) {
                TextButton(onClick = onRetry) { Text("Retry") }
            }
            TextButton(onClick = onPinToggle) {
                Text(if (item.pinned) "Unpin" else "Pin")
            }
            OutlinedButton(onClick = onRemove) { Text("Remove") }
        }
    }
}

private fun phaseLabel(item: DownloadItemState): String = when (item.phase) {
    DownloadPhase.QUEUED -> "Queued"
    DownloadPhase.WAITING_FOR_NETWORK -> "Waiting for network"
    DownloadPhase.DOWNLOADING -> "Downloading"
    DownloadPhase.FAILED -> "Failed"
    DownloadPhase.CACHED -> "Cached"
}
