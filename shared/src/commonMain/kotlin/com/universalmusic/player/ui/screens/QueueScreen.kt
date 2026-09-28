package com.universalmusic.player.ui.screens

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
    val listState = rememberLazyListState()
    var confirmClear by remember { mutableStateOf(false) }
    var undoMessage by remember { mutableStateOf<String?>(null) }
    val playing = queue.current != null
    LaunchedEffect(Unit) {
        if (currentOrderPos > 0) {
            listState.scrollToItem(currentOrderPos.coerceAtMost(orderedItems.lastIndex))
        }
    }

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
                TextButton(
                    onClick = {
                        if (playing) confirmClear = true else {
                            container.clearPlaybackQueue()
                            undoMessage = "Queue cleared"
                        }
                    },
                    enabled = orderedItems.isNotEmpty(),
                ) { Text("Clear") }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close queue") }
            }
        }
        undoMessage?.let { message ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                TextButton(
                    onClick = {
                        if (container.player.undoQueueEdit()) undoMessage = null
                    },
                ) { Text("Undo") }
            }
        }
        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text("Clear the queue?") },
                text = { Text("This stops playback and removes every queued track. You can undo it immediately after.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmClear = false
                            container.clearPlaybackQueue()
                            undoMessage = "Queue cleared"
                        },
                    ) { Text("Clear queue") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
                },
            )
        }
        if (orderedItems.isEmpty()) {
            EmptyState(
                "Queue is empty",
                "Play a track or add one from Search or Library.",
            )
        } else {
            LazyColumn(state = listState) {
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
                            val density = LocalDensity.current
                            var dragDy by remember(item.id) { mutableFloatStateOf(0f) }
                            Row {
                                Icon(
                                    Icons.Default.DragHandle,
                                    contentDescription = "Drag to reorder ${item.track.title}",
                                    modifier = Modifier
                                        .padding(top = 12.dp)
                                        .pointerInput(item.id) {
                                            detectDragGesturesAfterLongPress(
                                                onDragEnd = { dragDy = 0f },
                                                onDragCancel = { dragDy = 0f },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    dragDy += dragAmount.y
                                                    val rowPx = with(density) { 72.dp.toPx() }
                                                    val steps = (dragDy / rowPx).toInt()
                                                    if (steps == 0) return@detectDragGesturesAfterLongPress
                                                    val queueNow = container.player.queue.queue.value
                                                    val from = queueNow.orderIndexOf(item.id)
                                                    if (from < 0) return@detectDragGesturesAfterLongPress
                                                    val to = (from + steps).coerceIn(0, queueNow.playbackOrder().lastIndex)
                                                    if (to != from) {
                                                        container.player.moveInPlaybackOrder(from, to)
                                                        dragDy = 0f
                                                    }
                                                },
                                            )
                                        },
                                )
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
                                IconButton(onClick = {
                                    container.player.removeFromQueue(item.id)
                                    undoMessage = "Removed ${item.track.title}"
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove ${item.track.title}")
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
