package com.universalmusic.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun PinnedSection(
    resolvedPins: List<ResolvedHomePin>,
    homePinsEmpty: Boolean,
    editingPins: Boolean,
    onToggleEditing: () -> Unit,
    onPlayPin: (ResolvedHomePin) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Pinned",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!homePinsEmpty) {
            TextButton(onClick = onToggleEditing) {
                Text(if (editingPins) "Done" else "Edit")
            }
        }
    }
    when {
        resolvedPins.isEmpty() -> {
            Text(
                "Pin playlists, albums or folders from Library",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        else -> {
            resolvedPins.forEachIndexed { index, resolved ->
                PinRow(
                    resolved = resolved,
                    editing = editingPins,
                    canMoveUp = index > 0,
                    canMoveDown = index < resolvedPins.lastIndex,
                    onPlay = { onPlayPin(resolved) },
                    onMoveUp = { onMoveUp(resolved.pin.id) },
                    onMoveDown = { onMoveDown(resolved.pin.id) },
                    onRemove = { onRemove(resolved.pin.id) },
                )
            }
        }
    }
}

@Composable
private fun PinRow(
    resolved: ResolvedHomePin,
    editing: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPlay: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val pin = resolved.pin
    val enabled = resolved.status == PinStatus.READY && !editing
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onPlay)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            when (resolved.status) {
                PinStatus.LOADING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else -> Icon(
                    Icons.Default.LibraryMusic,
                    contentDescription = null,
                    tint = when (resolved.status) {
                        PinStatus.READY -> MaterialTheme.colorScheme.primary
                        PinStatus.DISCONNECTED, PinStatus.RATE_LIMITED, PinStatus.ERROR, PinStatus.MISSING ->
                            MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    pin.title.ifBlank { pin.targetId },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                resolved.detail,
                style = MaterialTheme.typography.bodySmall,
                color = when (resolved.status) {
                    PinStatus.READY, PinStatus.EMPTY, PinStatus.LOADING ->
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.error
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (editing) {
            IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = "Unpin ${pin.title}")
            }
        } else if (resolved.status == PinStatus.READY) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Play ${pin.title}",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
