package com.universalmusic.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import com.universalmusic.player.data.settings.NowPlayingDock
import com.universalmusic.player.ui.theme.LocalKainosPalette
import com.universalmusic.player.ui.theme.LocalSkinTokens
import com.universalmusic.player.ui.theme.Skin
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

/**
 * A desktop window much taller than wide (a rotated monitor, such as a 1440x2560 portrait panel):
 * the automatic dock puts Now Playing in a band across the bottom instead of a side column.
 */
fun desktopPortrait(maxWidth: Dp, maxHeight: Dp): Boolean =
    maxWidth >= DesktopNarrowMaxWidth && maxHeight >= maxWidth * 1.3f

/** The window edge the Now Playing pane sits against. Left and right are tall panes; top and bottom are bands. */
enum class DockEdge(val setting: NowPlayingDock) {
    LEFT(NowPlayingDock.LEFT),
    RIGHT(NowPlayingDock.RIGHT),
    TOP(NowPlayingDock.TOP),
    BOTTOM(NowPlayingDock.BOTTOM);

    val isSide: Boolean get() = this == LEFT || this == RIGHT
}

fun resolveDockEdge(setting: NowPlayingDock, portrait: Boolean): DockEdge = when (setting) {
    NowPlayingDock.AUTO -> if (portrait) DockEdge.BOTTOM else DockEdge.RIGHT
    NowPlayingDock.LEFT -> DockEdge.LEFT
    NowPlayingDock.RIGHT -> DockEdge.RIGHT
    NowPlayingDock.TOP -> DockEdge.TOP
    NowPlayingDock.BOTTOM -> DockEdge.BOTTOM
}

/**
 * The share of the window's width or height, measured in from each edge, that counts as that edge's
 * drop zone. Anything further in is the middle, where letting go leaves the player where it was.
 */
const val DockDropReach: Float = .3f

/**
 * The edge a drag released at ([x], [y]) in a [width] x [height] window drops the player on: the
 * nearest edge, measured as a share of the window so wide windows do not favour top and bottom.
 */
fun dockEdgeAt(x: Float, y: Float, width: Float, height: Float): DockEdge? {
    if (width <= 0f || height <= 0f) return null
    val distances = listOf(
        DockEdge.LEFT to x / width,
        DockEdge.RIGHT to (width - x) / width,
        DockEdge.TOP to y / height,
        DockEdge.BOTTOM to (height - y) / height,
    )
    val (edge, distance) = distances.minBy { it.second }
    return edge.takeIf { distance <= DockDropReach }
}

val DesktopNowPlayingBandMinHeight: Dp = 240.dp

/** The band never takes more than this share of the window, so the library keeps most of the height. */
const val DesktopNowPlayingBandMaxShare: Float = .45f

/** 300dp is the shortest band that still shows the heart / repeat / shuffle row below the transport. */
fun defaultNowPlayingBandHeight(windowHeight: Dp): Dp = (windowHeight * .18f).coerceIn(300.dp, 400.dp)

fun clampNowPlayingBandHeight(height: Dp, windowHeight: Dp): Dp =
    height.coerceIn(DesktopNowPlayingBandMinHeight, maxOf(DesktopNowPlayingBandMinHeight, windowHeight * DesktopNowPlayingBandMaxShare))

fun desktopLayoutBand(maxWidth: Dp): DesktopLayoutBand = when {
    maxWidth < DesktopNarrowMaxWidth -> DesktopLayoutBand.Narrow
    maxWidth >= DesktopWideMinWidth -> DesktopLayoutBand.Wide
    else -> DesktopLayoutBand.Standard
}

@Composable
internal fun rememberNowPlayingPaneExpanded(compactPlayer: Boolean): MutableState<Boolean> =
    rememberSaveable(compactPlayer) {
        mutableStateOf(!compactPlayer)
    }

/** First-run suppression never blocks paired controls or an explicit request to open the pane. */
internal fun nowPlayingPaneShown(
    expanded: Boolean,
    nothingEverPlayed: Boolean,
    remoteAvailable: Boolean = false,
    explicitlyOpened: Boolean = false,
): Boolean = expanded && (!nothingEverPlayed || remoteAvailable || explicitlyOpened)

/**
 * Drag to resize the pane beside it. [vertical] drags a horizontal seam up and down (the portrait
 * band). Frost's seam is the gap between two glass sheets, so it only shows while hovered.
 */
@Composable
fun DesktopResizeHandle(
    onDragWidthDelta: (Dp) -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
) {
    val density = LocalDensity.current
    // The gesture outlives recompositions; always report to the latest callback.
    val onDrag by rememberUpdatedState(onDragWidthDelta)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val frost = LocalSkinTokens.current.skin == Skin.FROST
    val thickness = if (frost) 10.dp else 6.dp
    val idle = if (frost) Color.Transparent else MaterialTheme.colorScheme.outlineVariant
    val active = if (frost) LocalKainosPalette.current.rule else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    Box(
        modifier
            .then(if (vertical) Modifier.height(thickness).fillMaxWidth() else Modifier.width(thickness).fillMaxHeight())
            .hoverable(interaction)
            .background(if (hovered) active else idle)
            .pointerInput(vertical) {
                if (vertical) {
                    detectVerticalDragGestures { _, dragAmount -> onDrag(with(density) { dragAmount.toDp() }) }
                } else {
                    detectHorizontalDragGestures { _, dragAmount -> onDrag(with(density) { dragAmount.toDp() }) }
                }
            },
    )
}
