package com.universalmusic.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Window width below this uses a single content pane (no permanent Now Playing column). */
val DesktopNarrowMaxWidth: Dp = 840.dp

/** At/above this width, Queue can sit beside Library while Now Playing stays open. */
val DesktopWideMinWidth: Dp = 1200.dp

val DesktopNowPlayingPaneMinWidth: Dp = 280.dp
val DesktopNowPlayingPaneMaxWidth: Dp = 560.dp
val DesktopNowPlayingPaneDefaultWidth: Dp = 380.dp
val DesktopQueueBesideWidth: Dp = 320.dp

enum class DesktopLayoutBand {
    /** Phone-like: overlays + bottom mini player; no permanent side pane. */
    Narrow,

    /** Content + collapsible/resizable Now Playing pane. */
    Standard,

    /** Content + optional Queue column beside Library + Now Playing pane. */
    Wide,
}

fun desktopLayoutBand(maxWidth: Dp): DesktopLayoutBand = when {
    maxWidth < DesktopNarrowMaxWidth -> DesktopLayoutBand.Narrow
    maxWidth >= DesktopWideMinWidth -> DesktopLayoutBand.Wide
    else -> DesktopLayoutBand.Standard
}

@Composable
fun DesktopResizeHandle(
    onDragWidthDelta: (Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier
            .width(6.dp)
            .fillMaxHeight()
            .hoverable(interaction)
            .background(
                if (hovered) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
            )
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dragAmount ->
                    onDragWidthDelta(with(density) { dragAmount.toDp() })
                }
            },
    )
}
