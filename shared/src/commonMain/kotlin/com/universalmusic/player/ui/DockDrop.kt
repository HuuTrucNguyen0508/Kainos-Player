package com.universalmusic.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.universalmusic.player.ui.components.GlassDensity
import com.universalmusic.player.ui.components.SkinPane
import com.universalmusic.player.ui.components.albumLightStrip
import com.universalmusic.player.ui.theme.LocalKainosPalette
import com.universalmusic.player.ui.theme.LocalSkinTokens
import com.universalmusic.player.ui.theme.Skin
import kotlin.math.roundToInt

/*
 * Dock motion: "carrying the light". The player is the app's lamp (album light), so picking it up
 * puts a small lantern card in the hand, the edge it nears fills with the cover's light from the
 * edge inward, and on drop the lantern sinks into that edge while the pane grows out of it. One
 * easing throughout (the app's standard cubic), 160-300 ms, no bounce in anything that lays out.
 */

internal val DockEasing = CubicBezierEasing(.2f, 0f, 0f, 1f)

/** A slot growing out of or folding into its edge; Folio moves a shade quicker, as its other motion does. */
@Composable
internal fun dockSlotSpec(): FiniteAnimationSpec<IntSize> =
    tween(if (LocalSkinTokens.current.skin == Skin.FOLIO) 240 else 280, easing = DockEasing)

@Composable
internal fun dockFadeSpec(): FiniteAnimationSpec<Float> = tween(180, easing = DockEasing)

private const val OverlayInMs = 180
private const val OverlayOutMs = 160
private const val ZoneArriveMs = 260
private const val ZoneEmphasisMs = 160
private const val CarryInMs = 160
private const val CarrySettleMs = 240

/** What the last release did: the edge the carried card sinks into, or null to settle back onto the grip. */
internal data class DockRelease(val edge: DockEdge?, val sequence: Int)

/** One drag of the player's grip, in the coordinates of the [DockDropHost] that shows the drop zones. */
@Stable
internal class DockDragState {
    var pointer by mutableStateOf<Offset?>(null)
        private set

    /** Where the grip was pressed: a cancelled carry settles back here. */
    var anchor by mutableStateOf(Offset.Zero)
        private set
    var lastRelease by mutableStateOf<DockRelease?>(null)
        private set
    var hostSize by mutableStateOf(IntSize.Zero)
        internal set
    internal var hostOrigin: Offset = Offset.Zero
    private var releases = 0

    val dragging: Boolean get() = pointer != null

    /** The edge a release right now would dock to; null in the middle of the window. */
    val target: DockEdge?
        get() = pointer?.let { dockEdgeAt(it.x, it.y, hostSize.width.toFloat(), hostSize.height.toFloat()) }

    fun start(inRoot: Offset) {
        anchor = inRoot - hostOrigin
        pointer = anchor
    }

    fun move(inRoot: Offset) {
        if (pointer != null) pointer = inRoot - hostOrigin
    }

    /** Escape: drop nothing. Returns whether a drag was under way. */
    fun cancel(): Boolean {
        if (!dragging) return false
        end(null)
        return true
    }

    fun release(): DockEdge? = target.also { end(it) }

    private fun end(edge: DockEdge?) {
        pointer = null
        lastRelease = DockRelease(edge, ++releases)
    }
}

/**
 * Fills the window and, while the grip is held, shows the four edges the player can dock to and the
 * [carry] card in the hand. [light] is the current cover's album light; it fills the chosen edge.
 */
@Composable
internal fun DockDropHost(
    drag: DockDragState,
    current: DockEdge,
    sidePaneWidth: Dp,
    bandHeight: Dp,
    light: Color = MaterialTheme.colorScheme.primary,
    carry: (@Composable () -> Unit)? = null,
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
        val density = LocalDensity.current
        val zones = dockZoneRects(
            width = drag.hostSize.width.toFloat(),
            height = drag.hostSize.height.toFloat(),
            sideWidth = with(density) { sidePaneWidth.toPx() },
            bandDepth = with(density) { bandHeight.toPx() },
            inset = with(density) { ZoneInset.toPx() },
        )
        // Once let go, the pointer is gone; the zones fade out as they were at release, not reset.
        var shownTarget by remember { mutableStateOf<DockEdge?>(null) }
        if (drag.dragging) shownTarget = drag.target
        AnimatedVisibility(
            visible = drag.dragging,
            enter = fadeIn(tween(OverlayInMs, easing = DockEasing)),
            exit = fadeOut(tween(OverlayOutMs, easing = DockEasing)),
        ) {
            DockDropZones(zones, target = shownTarget, current = current, light = light)
        }
        if (carry != null) DockCarry(drag, zones, light, carry)
    }
}

private val ZoneInset = 12.dp

/** Each zone is the footprint the player would take there, kept clear of the window's middle. */
internal fun dockZoneRects(width: Float, height: Float, sideWidth: Float, bandDepth: Float, inset: Float): Map<DockEdge, Rect> {
    val side = minOf(sideWidth, width * .28f)
    val depth = minOf(bandDepth, height * .28f)
    val bandWidth = (width - side * 2 - inset * 4).coerceAtLeast(0f)
    val sideHeight = (height - inset * 2).coerceAtLeast(0f)
    val bandLeft = inset * 2 + side
    return mapOf(
        DockEdge.LEFT to Rect(Offset(inset, inset), Size(side, sideHeight)),
        DockEdge.RIGHT to Rect(Offset(width - inset - side, inset), Size(side, sideHeight)),
        DockEdge.TOP to Rect(Offset(bandLeft, inset), Size(bandWidth, depth)),
        DockEdge.BOTTOM to Rect(Offset(bandLeft, height - inset - depth), Size(bandWidth, depth)),
    )
}

@Composable
private fun DockDropZones(zones: Map<DockEdge, Rect>, target: DockEdge?, current: DockEdge, light: Color) {
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme
    // The edges open: each zone slides in from its own edge as the dim settles over the app.
    val arrive = remember { Animatable(0f) }
    LaunchedEffect(Unit) { arrive.animateTo(1f, tween(ZoneArriveMs, easing = DockEasing)) }
    // Dim the app enough that the zones, not the screen under them, are what the eye reads.
    Box(Modifier.fillMaxSize().background(scheme.background.copy(alpha = .84f))) {
        for ((edge, rect) in zones) {
            val slide = with(density) { (18.dp.toPx() * (1f - arrive.value)) }
            val from = when (edge) {
                DockEdge.LEFT -> Offset(-slide, 0f)
                DockEdge.RIGHT -> Offset(slide, 0f)
                DockEdge.TOP -> Offset(0f, -slide)
                DockEdge.BOTTOM -> Offset(0f, slide)
            }
            DropZone(
                edge = edge,
                chosen = edge == target,
                here = edge == current,
                light = light,
                modifier = Modifier
                    .offset { IntOffset((rect.left + from.x).roundToInt(), (rect.top + from.y).roundToInt()) }
                    .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() }),
            )
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

/**
 * One edge. Idle: a dashed outline. Here: a quiet solid hairline, since the player already sits
 * there. Chosen: swells inward from its edge and fills with the cover's light spilling in from it.
 */
@Composable
private fun DropZone(edge: DockEdge, chosen: Boolean, here: Boolean, light: Color, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalSkinTokens.current
    val radius = tokens.rounding.large
    val spec = tween<Float>(ZoneEmphasisMs, easing = DockEasing)
    val glow by animateFloatAsState(if (chosen) 1f else 0f, spec, label = "zoneGlow")
    val scale by animateFloatAsState(if (chosen) 1f else .97f, spec, label = "zoneScale")
    val stroke by animateDpAsState(if (chosen) 2.dp else 1.dp, tween(ZoneEmphasisMs, easing = DockEasing), label = "zoneStroke")
    val fill by animateColorAsState(
        if (chosen) scheme.surfaceContainerHigh.copy(alpha = .55f) else scheme.surfaceContainerHigh.copy(alpha = if (here) .5f else .35f),
        tween(ZoneEmphasisMs, easing = DockEasing),
        label = "zoneFill",
    )
    val ink by animateColorAsState(if (chosen) scheme.primary else scheme.onSurfaceVariant, tween(ZoneEmphasisMs, easing = DockEasing), label = "zoneInk")
    val outline = when {
        chosen -> scheme.primary
        here -> scheme.onSurfaceVariant.copy(alpha = .6f)
        else -> scheme.onSurfaceVariant.copy(alpha = .45f)
    }
    val dashed = !chosen && !here
    // The zone grows toward the window's middle, out of the edge it belongs to.
    val origin = when (edge) {
        DockEdge.LEFT -> TransformOrigin(0f, .5f)
        DockEdge.RIGHT -> TransformOrigin(1f, .5f)
        DockEdge.TOP -> TransformOrigin(.5f, 0f)
        DockEdge.BOTTOM -> TransformOrigin(.5f, 1f)
    }
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = origin
            }
            .drawBehind {
                val corner = CornerRadius(radius.toPx())
                drawRoundRect(fill, cornerRadius = corner)
                if (glow > 0f) {
                    val edgeMid = when (edge) {
                        DockEdge.LEFT -> Offset(0f, size.height / 2)
                        DockEdge.RIGHT -> Offset(size.width, size.height / 2)
                        DockEdge.TOP -> Offset(size.width / 2, 0f)
                        DockEdge.BOTTOM -> Offset(size.width / 2, size.height)
                    }
                    drawRoundRect(
                        Brush.radialGradient(
                            0f to light.copy(alpha = .42f * glow),
                            .55f to light.copy(alpha = .16f * glow),
                            1f to Color.Transparent,
                            center = edgeMid,
                            radius = size.maxDimension * .9f,
                        ),
                        cornerRadius = corner,
                    )
                }
                val w = stroke.toPx()
                drawRoundRect(
                    color = outline,
                    topLeft = Offset(w / 2, w / 2),
                    size = Size(size.width - w, size.height - w),
                    cornerRadius = corner,
                    style = Stroke(
                        w,
                        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())) else null,
                    ),
                )
            },
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

/**
 * The card in the hand while the grip is held: hangs just under the pointer on a stiff spring, so
 * it trails the hand by a beat. On drop it sinks into the chosen edge; cancelled, it settles back
 * onto the grip it came from.
 */
@Composable
private fun DockCarry(drag: DockDragState, zones: Map<DockEdge, Rect>, light: Color, content: @Composable () -> Unit) {
    val position = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val presence = remember { Animatable(0f) }
    var held by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val pointer = drag.pointer
    LaunchedEffect(pointer) {
        if (pointer != null) {
            if (!held) {
                position.snapTo(pointer)
                held = true
            }
            position.animateTo(pointer, spring(Spring.DampingRatioNoBouncy, stiffness = 1_100f))
        } else if (held) {
            held = false
            val settle = drag.lastRelease?.edge?.let { zones[it]?.center } ?: drag.anchor
            position.animateTo(settle, tween(CarrySettleMs, easing = DockEasing))
        }
    }
    LaunchedEffect(held) {
        presence.animateTo(if (held) 1f else 0f, tween(if (held) CarryInMs else CarrySettleMs, easing = DockEasing))
    }
    if (!held && presence.value == 0f) return
    val density = LocalDensity.current
    val hang = with(density) { 14.dp.toPx() }
    Box(
        Modifier
            .offset {
                val at = position.value
                IntOffset((at.x - size.width / 2f).roundToInt(), (at.y + hang).roundToInt())
            }
            .onSizeChanged { size = it }
            .graphicsLayer {
                val p = presence.value
                alpha = p
                val s = .88f + .12f * p
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(.5f, 0f)
            },
    ) {
        CarryCard(light, content)
    }
}

/** The lantern: a small pane in the skin's own material, washed in the cover's light under the cover. */
@Composable
private fun CarryCard(light: Color, content: @Composable () -> Unit) {
    val tokens = LocalSkinTokens.current
    val dark = LocalKainosPalette.current.isDark
    val shape = RoundedCornerShape(tokens.rounding.large)
    val body: @Composable () -> Unit = {
        Box(Modifier.albumLightStrip(light, dark).widthIn(min = 200.dp, max = 280.dp)) { content() }
    }
    when (tokens.skin) {
        Skin.FROST -> SkinPane(density = GlassDensity.Dense, content = body)
        Skin.FOLIO -> Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, LocalKainosPalette.current.hair),
            shadowElevation = 2.dp,
            content = body,
        )
        Skin.CAELESTIA -> Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 10.dp,
            content = body,
        )
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
