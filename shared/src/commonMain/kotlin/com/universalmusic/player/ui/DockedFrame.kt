package com.universalmusic.player.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.universalmusic.player.ui.components.FrostSheet
import com.universalmusic.player.ui.components.GlassDensity
import com.universalmusic.player.ui.components.SkinPane

/**
 * The desktop frame: rail, then the player on whichever [dock] edge, wrapped around the content.
 * The content column keeps one call site whatever the dock, so moving the player never resets a
 * screen's saved state (Library's scroll, the open tab).
 */
@Composable
internal fun DockedFrame(
    dock: DockEdge,
    frost: Boolean,
    modifier: Modifier,
    rail: (@Composable () -> Unit)?,
    /** Shown only on a side dock: the player, or the Queue standing in for it. */
    sidePane: (@Composable () -> Unit)?,
    sidePaneWidth: Dp,
    /** Grows the side pane by the given amount; null shows a plain seam instead of a handle. */
    onGrowSidePane: ((Dp) -> Unit)?,
    /** Shown only on a top or bottom dock. */
    band: (@Composable () -> Unit)?,
    bandHeight: Dp,
    onGrowBand: (Dp) -> Unit,
    notices: @Composable ColumnScope.() -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val side = sidePane.takeIf { dock.isSide }
    val strip = band.takeIf { !dock.isSide }
    Row(modifier) {
        if (rail != null) {
            SheetOrPlain(frost, Modifier.fillMaxHeight()) { rail() }
            PaneSeam(frost)
        }
        if (side != null && dock == DockEdge.LEFT) {
            SidePane(sidePaneWidth, side)
            SideSeam(frost, onGrowSidePane, growsTowardPointer = true)
        }
        Column(Modifier.weight(1f).fillMaxSize()) {
            notices()
            if (strip != null && dock == DockEdge.TOP) {
                BandPane(bandHeight, strip)
                DesktopResizeHandle(vertical = true, onDragWidthDelta = { onGrowBand(it) })
            }
            Row(Modifier.weight(1f).fillMaxWidth()) { content() }
            if (strip != null && dock == DockEdge.BOTTOM) {
                DesktopResizeHandle(vertical = true, onDragWidthDelta = { onGrowBand(-it) })
                BandPane(bandHeight, strip)
            }
            footer()
        }
        if (side != null && dock == DockEdge.RIGHT) {
            SideSeam(frost, onGrowSidePane, growsTowardPointer = false)
            SidePane(sidePaneWidth, side)
        }
    }
}

@Composable
private fun SidePane(width: Dp, content: @Composable () -> Unit) {
    SkinPane(Modifier.width(width).fillMaxHeight(), content = content)
}

@Composable
private fun BandPane(height: Dp, content: @Composable () -> Unit) {
    SkinPane(Modifier.fillMaxWidth().height(height), content = content)
}

/** A left pane grows as its seam moves right, toward the pointer; a right pane grows as it moves left. */
@Composable
private fun SideSeam(frost: Boolean, onGrow: ((Dp) -> Unit)?, growsTowardPointer: Boolean) {
    if (onGrow == null) {
        PaneSeam(frost)
    } else {
        DesktopResizeHandle(onDragWidthDelta = { onGrow(if (growsTowardPointer) it else -it) })
    }
}

/** A Frost glass sheet around [content]; other skins lay it straight on the ground. */
@Composable
internal fun SheetOrPlain(frost: Boolean, modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    if (frost) FrostSheet(modifier, GlassDensity.Tall, content = content) else Box(modifier, content = content)
}

/** The gap between two Frost sheets, or the hairline between two plain panes. */
@Composable
internal fun PaneSeam(frost: Boolean) {
    if (frost) {
        Spacer(Modifier.width(10.dp))
    } else {
        Box(
            Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

/** One drag of the player's grip, in the coordinates of the [DockDropHost] that shows the drop zones. */
@Stable
internal class DockDragState {
    var pointer by mutableStateOf<Offset?>(null)
        private set
    var hostSize by mutableStateOf(IntSize.Zero)
        internal set
    internal var hostOrigin: Offset = Offset.Zero

    val dragging: Boolean get() = pointer != null

    /** The edge a release right now would dock to; null in the middle of the window. */
    val target: DockEdge?
        get() = pointer?.let { dockEdgeAt(it.x, it.y, hostSize.width.toFloat(), hostSize.height.toFloat()) }

    fun start(inRoot: Offset) {
        pointer = inRoot - hostOrigin
    }

    fun move(inRoot: Offset) {
        if (pointer != null) pointer = inRoot - hostOrigin
    }

    /** Escape: drop nothing. Returns whether a drag was under way. */
    fun cancel(): Boolean = dragging.also { pointer = null }

    fun release(): DockEdge? = target.also { pointer = null }
}

/** Fills the window and, while the grip is held, shows the four edges the player can dock to. */
@Composable
internal fun DockDropHost(
    drag: DockDragState,
    current: DockEdge,
    sidePaneWidth: Dp,
    bandHeight: Dp,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                drag.hostOrigin = it.positionInRoot()
                drag.hostSize = it.size
            }
            // Ahead of the focused field (Library search clears itself on Escape), so Escape always cancels a drag.
            .onPreviewKeyEvent { event ->
                if (event.key != Key.Escape || !drag.dragging) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) drag.cancel()
                true
            },
    ) {
        content()
        if (drag.dragging) DockDropZones(drag, current, sidePaneWidth, bandHeight)
    }
}

/**
 * Press and drag to carry the player to another edge. Dropping on its own edge, in the middle of the
 * window, or pressing Escape leaves it where it was.
 */
@Composable
internal fun DockGrip(
    drag: DockDragState,
    current: DockEdge,
    onDock: (DockEdge) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit = {},
) {
    val dock by rememberUpdatedState(onDock)
    val here by rememberUpdatedState(current)
    var origin by remember { mutableStateOf(Offset.Zero) }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val lit = hovered || drag.dragging
    Row(
        modifier
            .onGloballyPositioned { origin = it.positionInRoot() }
            .hoverable(hover)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(drag) {
                detectDragGestures(
                    onDragStart = { drag.start(origin + it) },
                    onDragEnd = { drag.release()?.takeIf { it != here }?.let(dock) },
                    onDragCancel = { drag.cancel() },
                    onDrag = { change, _ ->
                        change.consume()
                        drag.move(origin + change.position)
                    },
                )
            }
            .semantics { contentDescription = "Drag to move the player to another edge" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (current.isSide) {
            GripDots(rows = 3, columns = 2, lit = lit, horizontalPad = 10.dp, verticalPad = 8.dp)
        } else {
            // Fits inside the band's top margin, so the band's controls never scroll under it.
            GripDots(rows = 2, columns = 3, lit = lit, horizontalPad = 20.dp, verticalPad = 4.dp)
        }
        content()
    }
}

/** A small field of studs: the one mark in the app that says "this part moves". */
@Composable
private fun GripDots(rows: Int, columns: Int, lit: Boolean, horizontalPad: Dp, verticalPad: Dp) {
    val scheme = MaterialTheme.colorScheme
    val color by animateColorAsState(
        if (lit) scheme.primary else scheme.onSurfaceVariant.copy(alpha = .5f),
        tween(120),
    )
    val dot = 3.dp
    val gap = 3.dp
    Canvas(
        Modifier
            .padding(horizontal = horizontalPad, vertical = verticalPad)
            .size(width = dot * columns + gap * (columns - 1), height = dot * rows + gap * (rows - 1)),
    ) {
        val d = dot.toPx()
        val step = d + gap.toPx()
        for (r in 0 until rows) for (c in 0 until columns) {
            drawCircle(color, d / 2, Offset(c * step + d / 2, r * step + d / 2))
        }
    }
}

@Composable
private fun DockDropZones(drag: DockDragState, current: DockEdge, sidePaneWidth: Dp, bandHeight: Dp) {
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme
    val target = drag.target
    val width = with(density) { drag.hostSize.width.toDp() }
    val height = with(density) { drag.hostSize.height.toDp() }
    // Each zone is the footprint the player would take there, kept clear of the window's middle.
    val inset = 12.dp
    val sideWidth = minOf(sidePaneWidth, width * .28f)
    val bandDepth = minOf(bandHeight, height * .28f)
    val bandWidth = (width - sideWidth * 2 - inset * 4).coerceAtLeast(0.dp)
    // Dim the app enough that the zones, not the screen under them, are what the eye reads.
    Box(Modifier.fillMaxSize().background(scheme.background.copy(alpha = .84f))) {
        for (edge in DockEdge.entries) {
            val zone = when (edge) {
                DockEdge.LEFT -> Modifier.offset(inset, inset).size(sideWidth, height - inset * 2)
                DockEdge.RIGHT -> Modifier.offset(width - inset - sideWidth, inset).size(sideWidth, height - inset * 2)
                DockEdge.TOP -> Modifier.offset(inset * 2 + sideWidth, inset).size(bandWidth, bandDepth)
                DockEdge.BOTTOM -> Modifier.offset(inset * 2 + sideWidth, height - inset - bandDepth).size(bandWidth, bandDepth)
            }
            DropZone(edge, chosen = edge == target, here = edge == current, modifier = zone)
        }
        Surface(
            modifier = Modifier.align(Alignment.Center),
            shape = RoundedCornerShape(50),
            color = scheme.surfaceContainerHigh,
            contentColor = scheme.onSurface,
            shadowElevation = 6.dp,
        ) {
            Text(
                dropHint(target, current),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun DropZone(edge: DockEdge, chosen: Boolean, here: Boolean, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val radius = 14.dp
    val fill by animateColorAsState(
        if (chosen) scheme.primary.copy(alpha = .2f) else scheme.surfaceContainerHigh.copy(alpha = .4f),
        tween(120),
    )
    val ink by animateColorAsState(if (chosen) scheme.primary else scheme.onSurfaceVariant, tween(120))
    Box(
        modifier
            .background(fill, RoundedCornerShape(radius))
            .then(
                if (chosen) {
                    Modifier.border(2.dp, scheme.primary, RoundedCornerShape(radius))
                } else {
                    Modifier.drawBehind {
                        val stroke = 1.5.dp.toPx()
                        drawRoundRect(
                            color = scheme.onSurfaceVariant.copy(alpha = .45f),
                            topLeft = Offset(stroke / 2, stroke / 2),
                            size = size.copy(width = size.width - stroke, height = size.height - stroke),
                            cornerRadius = CornerRadius(radius.toPx()),
                            style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))),
                        )
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(edge.label, style = MaterialTheme.typography.titleMedium, color = ink, textAlign = TextAlign.Center)
            if (here) {
                Text("Player is here", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}

private val DockEdge.label: String
    get() = when (this) {
        DockEdge.LEFT -> "Left"
        DockEdge.RIGHT -> "Right"
        DockEdge.TOP -> "Top"
        DockEdge.BOTTOM -> "Bottom"
    }

private fun DockEdge.place(): String = when (this) {
    DockEdge.LEFT -> "on the left"
    DockEdge.RIGHT -> "on the right"
    DockEdge.TOP -> "at the top"
    DockEdge.BOTTOM -> "at the bottom"
}

internal fun dropHint(target: DockEdge?, current: DockEdge): String = when (target) {
    null -> "Drag to an edge to move the player. Esc cancels."
    current -> "Let go to keep the player ${current.place()}"
    else -> "Let go to put the player ${target.place()}"
}
