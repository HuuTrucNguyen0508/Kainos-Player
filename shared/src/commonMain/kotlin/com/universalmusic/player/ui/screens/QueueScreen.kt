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
import com.universalmusic.player.domain.model.QueueItem
import com.universalmusic.player.ui.components.EmptyState
import com.universalmusic.player.ui.components.TrackRow

@Composable
fun QueueScreen(
    container: AppContainer,
    onClose: () -> Unit,
) {
    val queue by container.player.queue.queue.collectAsState()
    val order = queue.playbackOrder()
    val orderedItems: List<Pair<Int, QueueItem>> = order.mapNotNull { storageIndex ->
        queue.items.getOrNull(storageIndex)?.let { storageIndex to it }
    }
    val currentOrderPos = order.indexOf(queue.currentIndex)

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Queue", style = MaterialTheme.typography.headlineSmall)
                if (queue.shuffle) {
                    Text(
                        "Shuffle order",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Row {
                TextButton(onClick = { container.clearPlaybackQueue() }) { Text("Clear") }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
            }
        }
        if (orderedItems.isEmpty()) {
            EmptyState(
                "Queue is empty",
                "Play a track or add one from Search or Library.",
            )
        } else {
            LazyColumn {
                itemsIndexed(orderedItems, key = { _, pair -> pair.second.id }) { orderPos, (storageIndex, item) ->
                    val isCurrent = orderPos == currentOrderPos
                    val sectionLabel = when {
                        isCurrent -> "Now playing"
                        orderPos == 0 && currentOrderPos > 0 -> "Played"
                        orderPos == currentOrderPos + 1 -> "Up next"
                        else -> null
                    }
                    sectionLabel?.let { label ->
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
                        )
                    }
                    TrackRow(
                        track = item.track,
                        onClick = { container.player.playQueueIndex(storageIndex) },
                        trailing = {
                            Row {
                                IconButton(
                                    onClick = {
                                        container.player.moveInPlaybackOrder(
                                            orderPos,
                                            (orderPos - 1).coerceAtLeast(0),
                                        )
                                    },
                                    enabled = orderPos > 0,
                                ) {
                                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                                }
                                IconButton(
                                    onClick = {
                                        container.player.moveInPlaybackOrder(
                                            orderPos,
                                            (orderPos + 1).coerceAtMost(orderedItems.lastIndex),
                                        )
                                    },
                                    enabled = orderPos < orderedItems.lastIndex,
                                ) {
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                                }
                                IconButton(onClick = { container.player.removeFromQueue(item.id) }) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove")
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
