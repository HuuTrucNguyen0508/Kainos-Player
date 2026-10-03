package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.playback.SleepTimerDurationPresetsMs
import com.universalmusic.player.domain.playback.formatSleepTimerRemaining
import kotlinx.coroutines.launch

@Composable
internal fun YouTubeMatchCorrectionDialog(
    track: Track,
    currentVideoId: String?,
    onDismiss: () -> Unit,
    onSelect: (youtubeVideoId: String) -> Unit,
    onClear: () -> Unit,
    searchCandidates: suspend (query: String?) -> List<Track>,
) {
    var candidates by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(track.canonicalId) {
        loading = true
        error = null
        runCatching { searchCandidates(null) }
            .onSuccess { candidates = it }
            .onFailure { error = it.message ?: "Search failed" }
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change YouTube match") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Keeps the Spotify identity and heart. Only the offline YouTube audio file changes. Spotify DRM is never downloaded.",
                    style = MaterialTheme.typography.bodySmall,
                )
                currentVideoId?.let {
                    Text("Current video: $it", style = MaterialTheme.typography.labelMedium)
                }
                when {
                    loading -> Text("Searching…", style = MaterialTheme.typography.bodySmall)
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    candidates.isEmpty() -> Text("No candidates found.", style = MaterialTheme.typography.bodySmall)
                    else -> candidates.take(8).forEach { candidate ->
                        val videoId = candidate.sources
                            .firstOrNull { it.provider == ProviderId.YOUTUBE_MUSIC }
                            ?.providerTrackId
                            ?: candidate.canonicalId.removePrefix("yt:").takeIf {
                                candidate.canonicalId.startsWith("yt:")
                            }
                        if (videoId != null) {
                            TextButton(
                                onClick = { onSelect(videoId) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "${candidate.title} · ${candidate.artistLine}",
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        loading = true
                        runCatching { searchCandidates(null) }
                            .onSuccess { candidates = it }
                            .onFailure { error = it.message ?: "Search failed" }
                        loading = false
                    }
                },
            ) { Text("Refresh") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) { Text("Clear match") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
internal fun SleepTimerDialog(
    active: Boolean,
    remainingMs: Long?,
    endOfTrack: Boolean,
    canEndOfTrack: Boolean,
    onDismiss: () -> Unit,
    onDuration: (Long) -> Unit,
    onEndOfTrack: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (active) "Sleep timer" else "Set sleep timer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (active) {
                    val status = when {
                        endOfTrack -> "Pauses at the end of this track."
                        remainingMs != null -> "Remaining ${formatSleepTimerRemaining(remainingMs)}."
                        else -> "Timer is active."
                    }
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                }
                SleepTimerDurationPresetsMs.forEach { ms ->
                    val minutes = (ms / 60_000L).toInt()
                    TextButton(
                        onClick = { onDuration(ms) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "$minutes min",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                        )
                    }
                }
                TextButton(
                    onClick = onEndOfTrack,
                    enabled = canEndOfTrack,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "End of current track",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                    )
                }
            }
        },
        confirmButton = {
            if (active) {
                TextButton(onClick = onCancel) { Text("Cancel timer") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
