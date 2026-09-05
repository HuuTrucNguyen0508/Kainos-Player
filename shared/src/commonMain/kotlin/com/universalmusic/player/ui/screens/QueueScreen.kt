package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.ui.components.EmptyState
import com.universalmusic.player.ui.components.TrackRow

@Composable
fun QueueScreen(
    container: AppContainer,
    onClose: () -> Unit,
) {
    val queue by container.player.queue.queue.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Queue", style = MaterialTheme.typography.headlineSmall)
            Row {
                TextButton(onClick = { container.player.queue.clear() }) { Text("Clear") }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
            }
        }
        if (queue.items.isEmpty()) {
            EmptyState("Queue is empty", "Play a track or add one from search. The queue stores unified tracks, not a single provider.")
        } else {
            LazyColumn {
                itemsIndexed(queue.items, key = { _, item -> item.id }) { index, item ->
                    val current = index == queue.currentIndex
                    TrackRow(
                        track = item.track,
                        onClick = { container.player.playQueueIndex(index) },
                        trailing = {
                            Row {
                                IconButton(onClick = { container.player.queue.move(index, (index - 1).coerceAtLeast(0)) }) {
                                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                                }
                                IconButton(onClick = { container.player.queue.move(index, (index + 1).coerceAtMost(queue.items.lastIndex)) }) {
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                                }
                                IconButton(onClick = { container.player.queue.remove(item.id) }) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove")
                                }
                            }
                        },
                    )
                    if (current) {
                        Text(
                            "Now playing",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 76.dp, bottom = 8.dp),
                        )
                    }
                }
            }
        }
        Button(
            onClick = { container.sample.allTracks.forEach { container.player.addToQueue(it) } },
            modifier = Modifier.padding(top = 12.dp),
        ) {
            Text("Add sample album to queue")
        }
    }
}
