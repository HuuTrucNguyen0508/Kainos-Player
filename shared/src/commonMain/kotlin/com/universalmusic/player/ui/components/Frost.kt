package com.universalmusic.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.size.Precision
import com.universalmusic.player.ui.theme.FrostGlass
import com.universalmusic.player.ui.theme.LocalDesktopWallpaper
import com.universalmusic.player.ui.theme.LocalKainosPalette
import com.universalmusic.player.ui.theme.LocalSkinTokens
import com.universalmusic.player.ui.theme.Skin
import com.universalmusic.player.ui.theme.frostGlass

/** How much of the wallpaper a Frost sheet lets through, after the shell's fills. */
enum class GlassDensity {
    /** Over bright mist only: the top band, chips. */
    Fog,

    /** Full-height sheets that cross from mist into rock (the shell's tall sidebar fill). */
    Tall,

    /** Bars that sit over the dark rock at the foot of the window. */
    Dense,
}

private class FrostBackdrop(val painter: Painter?, val size: IntSize)

private val LocalFrostBackdrop = staticCompositionLocalOf<FrostBackdrop?> { null }

/** True inside a [FrostSheet], where a card is a lift on the glass rather than a second sheet. */
private val LocalInsideFrostSheet = staticCompositionLocalOf { false }

@Composable
fun insideFrostSheet(): Boolean = LocalInsideFrostSheet.current

@Composable
fun isFrost(): Boolean = LocalSkinTokens.current.skin == Skin.FROST

@Composable
private fun glass(): FrostGlass = frostGlass(LocalKainosPalette.current.isDark)

/**
 * Frost's ground: the desktop wallpaper, cover-scaled to the window and lightly veiled, or a mist
 * gradient when there is no wallpaper. Glass sheets inside draw a blurred copy of the same picture.
 */
@Composable
fun FrostGround(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val glass = glass()
    val path = LocalDesktopWallpaper.current
    val context = LocalPlatformContext.current
    val painter = path?.let {
        val request = remember(it, context) {
            // A screen-sized decode is plenty: the picture is shown cover-scaled and mostly blurred.
            ImageRequest.Builder(context).data("file://$it").size(2560).precision(Precision.INEXACT).build()
        }
        rememberAsyncImagePainter(request)
    }
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.onSizeChanged { size = it }) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(Brush.verticalGradient(listOf(glass.mistTop, glass.mistBottom)))
            painter?.let { drawCover(it, this.size) }
            drawRect(glass.veil)
        }
        CompositionLocalProvider(LocalFrostBackdrop provides FrostBackdrop(painter, size)) { content() }
    }
}

/**
 * One Frost sheet: the wallpaper behind it blurred, the shell's white (Mist) or smoke (Smoke) fill
 * running top to bottom, a specular sheen in the top-left and a bright rim along the top edge.
 */
@Composable
fun FrostSheet(
    modifier: Modifier = Modifier,
    density: GlassDensity = GlassDensity.Tall,
    shape: Shape = RoundedCornerShape(22.dp),
    blur: Dp = 28.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val glass = glass()
    val backdrop = LocalFrostBackdrop.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val (top, bottom) = when (density) {
        GlassDensity.Fog -> glass.fogTop to glass.fogBottom
        GlassDensity.Tall -> glass.tallTop to glass.tallBottom
        GlassDensity.Dense -> glass.denseTop to glass.denseBottom
    }
    Box(
        modifier
            .onGloballyPositioned { origin = it.positionInRoot() }
            .clip(shape)
            .border(1.dp, Brush.verticalGradient(0f to glass.rim, .4f to glass.edge, 1f to glass.edge), shape),
    ) {
        val painter = backdrop?.painter
        if (painter != null && backdrop.size != IntSize.Zero) {
            Canvas(Modifier.matchParentSize().blur(blur, BlurredEdgeTreatment.Rectangle)) {
                translate(-origin.x, -origin.y) {
                    val ground = Size(backdrop.size.width.toFloat(), backdrop.size.height.toFloat())
                    drawCover(painter, ground)
                    drawRect(glass.veil, size = ground)
                }
            }
        }
        Box(
            Modifier.matchParentSize().drawBehind {
                drawRect(Brush.verticalGradient(listOf(top, bottom)))
                drawSpecular(glass.specularAlpha)
            },
        )
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurface,
            LocalInsideFrostSheet provides true,
        ) { content() }
    }
}

/**
 * A card on a Frost sheet: the shell's lift, a faint white wash over the glass with the same bright
 * top rim, so it reads one step lighter than the sheet (dark: raised #252629 over surface #1a1b1e).
 */
@Composable
fun FrostLift(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val glass = glass()
    Box(
        modifier
            .clip(shape)
            .drawBehind { drawRect(Brush.verticalGradient(listOf(glass.liftTop, glass.liftBottom))) }
            .border(1.dp, Brush.verticalGradient(0f to glass.rim.copy(alpha = glass.rim.alpha * .6f), .4f to glass.edge, 1f to glass.edge), shape),
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { content() }
    }
}

/** A side pane, queue column or player band: a Frost sheet, or the skin's plain low container. */
@Composable
fun SkinPane(
    modifier: Modifier = Modifier,
    density: GlassDensity = GlassDensity.Tall,
    content: @Composable () -> Unit,
) {
    if (isFrost()) {
        FrostSheet(modifier, density) { content() }
    } else {
        Surface(
            modifier,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            content = content,
        )
    }
}

private fun DrawScope.drawCover(painter: Painter, area: Size) {
    val intrinsic = painter.intrinsicSize
    if (intrinsic.isUnspecified() || intrinsic.width <= 0f || intrinsic.height <= 0f) return
    val scale = maxOf(area.width / intrinsic.width, area.height / intrinsic.height)
    val drawn = Size(intrinsic.width * scale, intrinsic.height * scale)
    translate((area.width - drawn.width) / 2, (area.height - drawn.height) / 2) {
        with(painter) { draw(drawn) }
    }
}

private fun Size.isUnspecified() = this == Size.Unspecified

/** radial-gradient(70% 55% at 18% 0%, white α, transparent 70%) from the shell's Tokens.qml. */
private fun DrawScope.drawSpecular(alpha: Float) {
    if (alpha <= 0f) return
    val rx = size.width * .70f
    val ry = size.height * .55f
    if (rx <= 0f || ry <= 0f) return
    val center = Offset(size.width * .18f, 0f)
    scale(1f, ry / rx, pivot = center) {
        drawCircle(
            Brush.radialGradient(0f to Color.White.copy(alpha = alpha), .70f to Color.Transparent, center = center, radius = rx),
            radius = rx,
            center = center,
        )
    }
}
