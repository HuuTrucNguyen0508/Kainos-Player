package com.universalmusic.player.ui.screens

import com.universalmusic.player.ui.components.AccentBadge
import com.universalmusic.player.ui.components.SectionRule
import com.universalmusic.player.ui.components.skinGround
import com.universalmusic.player.ui.theme.Accents
import androidx.compose.material.icons.filled.Shuffle
import com.universalmusic.player.ui.reportsTextInputFocus
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.ui.components.EmptyState
import com.universalmusic.player.domain.model.QueueItem
import com.universalmusic.player.ui.theme.AccentSlot

@Composable
fun QueueScreen(
    container: AppContainer,
    /** Wide windows: one line per song, with Move up and Move down in the song's menu. */
    dense: Boolean = false,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val presenter = remember(container) { QueuePresenter.from(container, scope) }
    val trackActions = LocalTrackActions.current ?: remember(container) { TrackActionsPresenter.from(container) }
    QueueContent(presenter, trackActions, dense, onClose)
}

@Composable
internal fun QueueContent(
    presenter: QueuePresenter,
    trackActions: TrackActionsPresenter,
    dense: Boolean,
    onClose: () -> Unit,
) {
    val state by presenter.state.collectAsState()
    val queue = state.queue
    val orderedItems = state.orderedItems
    val currentOrderPos = state.currentOrderPosition
    val listState = rememberLazyListState()
    var confirmClear by remember { mutableStateOf(false) }
    var savePlaylistDraft by remember { mutableStateOf<String?>(null) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var undoMessage by remember { mutableStateOf<String?>(null) }
    val playing = queue.current != null
    LaunchedEffect(Unit) {
        if (currentOrderPos > 0) {
            listState.scrollToItem(currentOrderPos.coerceAtMost(orderedItems.lastIndex))
        }
    }
    Column(Modifier.fillMaxSize().skinGround().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Queue", style = MaterialTheme.typography.headlineMedium)
                if (queue.shuffle) {
                    AccentBadge("Shuffle order", Accents.shuffle, icon = Icons.Default.Shuffle)
                }
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close queue") }
        }
        Row {
            TextButton(
                onClick = {
                    savePlaylistDraft = "Queue"
                },
                enabled = orderedItems.isNotEmpty(),
            ) { Text("Save as playlist") }
            TextButton(
                onClick = {
                    if (playing) confirmClear = true else {
                        presenter.clear()
                        undoMessage = "Queue cleared"
                    }
                },
                enabled = orderedItems.isNotEmpty(),
            ) { Text("Clear") }
        }
        saveMessage?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        if (savePlaylistDraft != null) {
            AlertDialog(
                onDismissRequest = { savePlaylistDraft = null },
                title = { Text("Save queue as playlist") },
                text = {
                    OutlinedTextField(
                        value = savePlaylistDraft!!,
                        onValueChange = { savePlaylistDraft = it },
                        singleLine = true,
                        label = { Text("Playlist name") },
                        modifier = Modifier.fillMaxWidth().reportsTextInputFocus(),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val name = savePlaylistDraft?.trim().orEmpty().ifBlank { "Queue" }
                            saveMessage = presenter.save(name)
                            savePlaylistDraft = null
                        },
                    ) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { savePlaylistDraft = null }) { Text("Cancel") }
                },
            )
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
                        if (presenter.undo()) undoMessage = null
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
                            presenter.clear()
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
                itemsIndexed(orderedItems, key = { _, pair -> pair.second.id }) { orderPos, (_, item) ->
                    val isCurrent = orderPos == currentOrderPos
                    when {
                        isCurrent -> SectionRule("Now playing", Modifier.padding(top = 8.dp))
                        orderPos == 0 && currentOrderPos > 0 -> SectionRule("Played", Modifier.padding(top = 8.dp))
                        orderPos == currentOrderPos + 1 -> SectionRule("Up next", Modifier.padding(top = 8.dp)) {
                            TextButton(onClick = {
                                val removed = presenter.clearUpcoming()
                                if (removed > 0) undoMessage = "Cleared ${countNoun(removed, "upcoming song", "upcoming songs")}"
                            }) { Text("Clear upcoming") }
                        }
                    }
                    QueueEntry(
                        item = item,
                        orderPos = orderPos,
                        lastOrderPos = orderedItems.lastIndex,
                        isCurrent = isCurrent,
                        dense = dense,
                        presenter = presenter,
                        trackActions = trackActions,
                        onRemoved = { undoMessage = "Removed ${item.track.title}" },
                    )
                }
                state.radio?.let { radio ->
                    item(key = "radio-footer") {
                        SectionRule(
                            radioFooterLabel(radio, state.upcomingCount),
                            Modifier.padding(top = 12.dp),
                            accent = AccentSlot.Purple,
                        ) {
                            TextButton(onClick = presenter::stopRadio) { Text("Stop radio") }
                        }
                    }
                }
            }
        }
    }
}

/** One queued song: the shared song menu, remove, and reordering by drag, arrows or menu. */
@Composable
private fun QueueEntry(
    item: QueueItem,
    orderPos: Int,
    lastOrderPos: Int,
    isCurrent: Boolean,
    dense: Boolean,
    presenter: QueuePresenter,
    trackActions: TrackActionsPresenter,
    onRemoved: () -> Unit,
) {
    val density = LocalDensity.current
    var rowHeightPx by remember(item.id) { mutableFloatStateOf(with(density) { (if (dense) 56.dp else 112.dp).toPx() }) }
    var dragDy by remember(item.id) { mutableFloatStateOf(0f) }
    val title = item.track.title
    val dragHandle = @Composable {
        Icon(
            Icons.Default.DragHandle,
            contentDescription = "Drag to reorder $title",
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .pointerInput(item.id) {
                    detectDragGesturesAfterLongPress(
                        onDragEnd = { dragDy = 0f },
                        onDragCancel = { dragDy = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragDy += dragAmount.y
                            val steps = (dragDy / rowHeightPx).toInt()
                            if (steps == 0) return@detectDragGesturesAfterLongPress
                            if (presenter.move(item.id, steps)) dragDy = 0f
                        },
                    )
                },
        )
    }
    val remove = @Composable {
        IconButton(onClick = {
            presenter.remove(item.id)
            onRemoved()
        }) {
            Icon(Icons.Default.Close, contentDescription = "Remove $title")
        }
    }
    Column(Modifier.onSizeChanged { rowHeightPx = it.height.toFloat() }) {
        ActionTrackRow(
            track = item.track,
            actions = trackActions,
            onClick = { presenter.play(item.id) },
            compact = dense,
            highlighted = isCurrent,
            trailing = if (dense) {
                {
                    dragHandle()
                    remove()
                }
            } else {
                null
            },
            // Dense rows have no arrow buttons, so the arrows' moves live in the menu.
            extras = if (dense) {
                listOfNotNull(
                    TrackMenuExtra("Move up", Icons.Default.KeyboardArrowUp) { presenter.move(item.id, -1) }
                        .takeIf { orderPos > 0 },
                    TrackMenuExtra("Move down", Icons.Default.KeyboardArrowDown) { presenter.move(item.id, 1) }
                        .takeIf { orderPos < lastOrderPos },
                )
            } else {
                emptyList()
            },
        )
        if (!dense) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                dragHandle()
                IconButton(onClick = { presenter.move(item.id, -1) }, enabled = orderPos > 0) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                }
                IconButton(onClick = { presenter.move(item.id, 1) }, enabled = orderPos < lastOrderPos) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                }
                remove()
            }
        }
    }
}
