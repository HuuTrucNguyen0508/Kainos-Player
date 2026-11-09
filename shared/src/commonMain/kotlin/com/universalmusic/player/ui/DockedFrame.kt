package com.universalmusic.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.universalmusic.player.ui.components.FrostSheet
import com.universalmusic.player.ui.components.GlassDensity
import com.universalmusic.player.ui.components.SkinPane

/**
 * The desktop frame: rail, then the player on whichever [dock] edge, wrapped around the content.
 * The content column keeps one call site whatever the dock, so moving the player never resets a
 * screen's saved state (Library's scroll, the open tab).
 *
 * Each edge is a slot that grows out of its edge when the player arrives and folds back into it when
 * the player leaves or hides. A leaving slot shows the pane's last drawn frame, not a second live
 * screen, so Now Playing is composed once however the frame is moving.
 */
@Composable
internal fun DockedFrame(
    dock: DockEdge,
    frost: Boolean,
    modifier: Modifier,
    layoutBand: DesktopLayoutBand? = null,
    portrait: Boolean = false,
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
    // One frozen picture per edge, owned here so it outlives the slot that recorded it.
    val leftGhost = rememberGraphicsLayer()
    val rightGhost = rememberGraphicsLayer()
    val topGhost = rememberGraphicsLayer()
    val bottomGhost = rememberGraphicsLayer()
    // Reset slot transitions on responsive changes; the content keeps its composition and scroll.
    val slotKey = layoutBand to portrait
    Row(modifier) {
        if (rail != null) {
            SheetOrPlain(frost, Modifier.fillMaxHeight()) { rail() }
            PaneSeam(frost)
        }
        SideSlot(DockEdge.LEFT, side.takeIf { dock == DockEdge.LEFT }, sidePaneWidth, leftGhost, slotKey, frost, onGrowSidePane)
        Column(Modifier.weight(1f).fillMaxSize()) {
            notices()
            BandSlot(DockEdge.TOP, strip.takeIf { dock == DockEdge.TOP }, bandHeight, topGhost, slotKey, onGrowBand)
            Row(Modifier.weight(1f).fillMaxWidth()) { content() }
            BandSlot(DockEdge.BOTTOM, strip.takeIf { dock == DockEdge.BOTTOM }, bandHeight, bottomGhost, slotKey, onGrowBand)
            footer()
        }
        SideSlot(DockEdge.RIGHT, side.takeIf { dock == DockEdge.RIGHT }, sidePaneWidth, rightGhost, slotKey, frost, onGrowSidePane)
    }
}

/** The pane and its seam, revealed from the window edge they sit against; folds back into it when [live] is gone. */
@Composable
private fun RowScope.SideSlot(
    edge: DockEdge,
    live: (@Composable () -> Unit)?,
    width: Dp,
    ghost: GraphicsLayer,
    layoutKey: Pair<DesktopLayoutBand?, Boolean>,
    frost: Boolean,
    onGrow: ((Dp) -> Unit)?,
) {
    val anchor = if (edge == DockEdge.LEFT) Alignment.Start else Alignment.End
    AnimatedVisibility(
        visibleState = remember(layoutKey) { MutableTransitionState(live != null) }.apply { targetState = live != null },
        enter = expandHorizontally(dockSlotSpec(), expandFrom = anchor) + fadeIn(dockFadeSpec()),
        exit = shrinkHorizontally(dockSlotSpec(), shrinkTowards = anchor) + fadeOut(dockFadeSpec()),
    ) {
        Row(Modifier.fillMaxHeight()) {
            if (edge == DockEdge.RIGHT) SideSeam(frost, onGrow, growsTowardPointer = false)
            val paneModifier = Modifier.width(width).fillMaxHeight()
            if (live != null) {
                SkinPane(paneModifier.recordInto(ghost), content = live)
            } else {
                GhostPane(paneModifier, ghost)
            }
            if (edge == DockEdge.LEFT) SideSeam(frost, onGrow, growsTowardPointer = true)
        }
    }
}

/** The band and its seam, revealed from the top or bottom edge; folds back into it when [live] is gone. */
@Composable
private fun ColumnScope.BandSlot(
    edge: DockEdge,
    live: (@Composable () -> Unit)?,
    height: Dp,
    ghost: GraphicsLayer,
    layoutKey: Pair<DesktopLayoutBand?, Boolean>,
    onGrow: (Dp) -> Unit,
) {
    val anchor = if (edge == DockEdge.TOP) Alignment.Top else Alignment.Bottom
    AnimatedVisibility(
        visibleState = remember(layoutKey) { MutableTransitionState(live != null) }.apply { targetState = live != null },
        enter = expandVertically(dockSlotSpec(), expandFrom = anchor) + fadeIn(dockFadeSpec()),
        exit = shrinkVertically(dockSlotSpec(), shrinkTowards = anchor) + fadeOut(dockFadeSpec()),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // The seam always sits on the band's inner side: under a top band, over a bottom one.
            if (edge == DockEdge.BOTTOM) DesktopResizeHandle(vertical = true, onDragWidthDelta = { onGrow(-it) })
            val paneModifier = Modifier.fillMaxWidth().height(height)
            if (live != null) {
                SkinPane(paneModifier.recordInto(ghost), content = live)
            } else {
                GhostPane(paneModifier, ghost)
            }
            if (edge == DockEdge.TOP) DesktopResizeHandle(vertical = true, onDragWidthDelta = { onGrow(it) })
        }
    }
}

/** Draws through to [layer] while keeping a copy, so the slot can still show the pane after it has left. */
private fun Modifier.recordInto(layer: GraphicsLayer): Modifier = drawWithContent {
    layer.record { this@drawWithContent.drawContent() }
    drawLayer(layer)
}

/** The pane's last frame, standing in while its slot folds away. */
@Composable
private fun GhostPane(modifier: Modifier, layer: GraphicsLayer) {
    Box(modifier.drawBehind { drawLayer(layer) })
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
                    onDragEnd = { drag.release(here)?.takeIf { it != here }?.let(dock) },
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
