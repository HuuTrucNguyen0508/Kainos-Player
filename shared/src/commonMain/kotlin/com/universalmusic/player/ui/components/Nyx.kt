package com.universalmusic.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.universalmusic.player.ui.theme.LocalDesktopWallpaper
import com.universalmusic.player.ui.theme.LocalNyxSpacing
import com.universalmusic.player.ui.theme.LocalSkinTokens
import com.universalmusic.player.ui.theme.NyxLightTokens
import com.universalmusic.player.ui.theme.Skin
import kotlin.math.roundToInt

/** Loads the desktop Nyx nebula bitmap at its exact 1:1 pixels; returns null on Android or if missing. */
@Composable
internal expect fun rememberNyxWallpaperBitmap(path: String?): ImageBitmap?

@Composable
fun isNyx(): Boolean = LocalSkinTokens.current.skin == Skin.NYX

/**
 * Builds a tiled [ShaderBrush] for the 1-device-pixel screen-door grid: black on the left edge
 * (`x == 0`) and bottom edge (`y == cellPx - 1`) of every cell, transparent inside.
 */
@Composable
fun rememberNyxGridBrush(cellPx: Int = LocalNyxSpacing.current.cellPx.roundToInt().coerceAtLeast(2)): ShaderBrush =
    remember(cellPx) {
        val bitmap = ImageBitmap(cellPx, cellPx)
        val drawSize = Size(cellPx.toFloat(), cellPx.toFloat())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, ComposeCanvas(bitmap), drawSize) {
            drawRect(NyxLightTokens.gridLine, Offset.Zero, Size(1f, drawSize.height))
            drawRect(NyxLightTokens.gridLine, Offset(0f, drawSize.height - 1f), Size(drawSize.width, 1f))
        }
        ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated))
    }

/**
 * Nyx's ground: pure black void with the treated nebula drawn 1:1 from window top-left with
 * [FilterQuality.None] (never scaled, so the baked-in 5 px grid never smears). On Android or when
 * the wallpaper file is missing, draws the flat `#000000` void with the 1 px grid.
 */
@Composable
fun NyxGround(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val path = LocalDesktopWallpaper.current
    val bitmap = rememberNyxWallpaperBitmap(path)
    val gridBrush = rememberNyxGridBrush()
    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(NyxLightTokens.gridLine)
            if (bitmap != null) {
                val src = IntSize(bitmap.width, bitmap.height)
                drawImage(
                    image = bitmap,
                    srcOffset = IntOffset.Zero,
                    srcSize = src,
                    dstOffset = IntOffset.Zero,
                    dstSize = src,
                    filterQuality = FilterQuality.None,
                )
            } else {
                drawRect(gridBrush)
            }
        }
        content()
    }
}

/** A Nyx slab panel (`slab` 82% or `slabOver` 92%) over the sky, with an optional 1 px top light. */
@Composable
fun NyxPane(
    modifier: Modifier = Modifier,
    over: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val fill = if (over) NyxLightTokens.slabOver else NyxLightTokens.slab
    Box(
        modifier.drawBehind {
            drawRect(fill)
            drawLine(NyxLightTokens.topLight, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1f)
        },
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            content()
        }
    }
}

/** Draws Nyx's violet-to-periwinkle selection wash, bottom under-glow, and 1 px top inner light. */
fun Modifier.nyxWash(shape: Shape, full: Boolean = false): Modifier =
    this.clip(shape).drawBehind {
        if (full) {
            drawRect(NyxLightTokens.washFullBase)
            drawRect(Brush.horizontalGradient(listOf(NyxLightTokens.washFullStart, NyxLightTokens.washFullEnd)))
            drawUnderGlow(NyxLightTokens.underGlowSmall, depthPx = 12f, alphaScale = 0.48f)
            drawLine(NyxLightTokens.topLightSmall, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1f)
        } else {
            drawRect(
                Brush.horizontalGradient(
                    0f to NyxLightTokens.washStart,
                    0.60f to NyxLightTokens.washMid,
                    1f to NyxLightTokens.washEnd,
                ),
            )
            drawUnderGlow(NyxLightTokens.underGlow, depthPx = 16f, alphaScale = 0.38f)
            drawLine(NyxLightTokens.topLight, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1f)
        }
    }

/** Draws Nyx's composer-style search input surface (subtle wash, bottom glow, violet hairline ring). */
fun Modifier.nyxInputSurface(shape: Shape): Modifier =
    this.clip(shape)
        .drawBehind {
            drawRect(NyxLightTokens.inputBase)
            drawRect(
                Brush.horizontalGradient(
                    0f to NyxLightTokens.inputWashStart,
                    0.60f to NyxLightTokens.inputWashMid,
                    1f to Color.Transparent,
                ),
            )
            drawUnderGlow(NyxLightTokens.underGlow, depthPx = 18f, alphaScale = 0.55f)
        }
        .border(1.dp, NyxLightTokens.inputRing, shape)

/**
 * Nyx's primary lit button (Play / Pause): radial top highlight + 150-degree violet-to-periwinkle
 * gradient over `#0d0b26`, inner bottom glow, `#b4aaff` 20% ring and a soft outer violet halo.
 */
fun Modifier.nyxGlowButton(shape: Shape): Modifier =
    this
        .drawBehind {
            val glowHeight = size.height * 0.55f
            val center = Offset(size.width / 2f, size.height * 0.85f)
            val radius = maxOf(size.width, size.height) * 0.82f
            drawRoundRect(
                brush = Brush.radialGradient(
                    0f to NyxLightTokens.buttonOuterGlow.copy(alpha = 0.36f),
                    0.65f to NyxLightTokens.buttonOuterGlow.copy(alpha = 0.12f),
                    1f to Color.Transparent,
                    center = center,
                    radius = radius,
                ),
                topLeft = Offset(-6f, 2f),
                size = Size(size.width + 12f, size.height + glowHeight * 0.35f),
                cornerRadius = CornerRadius(14f, 14f),
            )
        }
        .clip(shape)
        .drawBehind {
            drawRect(NyxLightTokens.buttonBase)
            drawRect(
                Brush.linearGradient(
                    0f to NyxLightTokens.buttonStart,
                    0.60f to NyxLightTokens.buttonMid,
                    1f to NyxLightTokens.buttonEnd,
                    start = Offset(0f, 0f),
                    end = Offset(size.width * 0.58f, size.height),
                ),
            )
            drawRect(
                Brush.radialGradient(
                    0f to NyxLightTokens.buttonTopHighlight,
                    0.70f to Color.Transparent,
                    center = Offset(size.width * 0.5f, 0f),
                    radius = maxOf(size.width * 0.8f, size.height * 0.65f),
                ),
            )
            drawUnderGlow(NyxLightTokens.buttonOuterGlow, depthPx = 14f, alphaScale = 0.55f)
            drawLine(NyxLightTokens.buttonTopHighlight, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1f)
        }
        .border(1.dp, NyxLightTokens.buttonRing, shape)

/** Overlays the 1-device-pixel screen-door grid on covers, placeholders and meters. */
fun Modifier.nyxScreenDoorOverlay(): Modifier = composed {
    val gridBrush = rememberNyxGridBrush()
    drawWithContent {
        drawContent()
        drawRect(gridBrush)
    }
}

/**
 * Grid-cut display title over open sky: heavy Rubik 800 drawn into an offscreen layer, filled with
 * the 170-degree display gradient (`#d2c8ff` -> `#9aa6ff`), cut on the 5 px screen-door lattice
 * aligned to window coordinates, and composited additively ([BlendMode.Plus]) onto the sky.
 */
@Composable
fun NyxCutTitle(
    text: String,
    style: TextStyle = MaterialTheme.typography.displayLarge,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    val cellPx = LocalNyxSpacing.current.cellPx.roundToInt().coerceAtLeast(2)
    val gridBrush = rememberNyxGridBrush(cellPx)
    var originInRoot by remember { mutableStateOf(Offset.Zero) }
    val plusPaint = remember { Paint().apply { blendMode = BlendMode.Plus } }

    Box(
        modifier
            .onGloballyPositioned { originInRoot = it.positionInRoot() }
            .drawWithContent {
                val rect = Rect(Offset.Zero, size)
                drawContext.canvas.saveLayer(rect, plusPaint)
                drawContent()
                drawRect(
                    brush = Brush.linearGradient(
                        listOf(NyxLightTokens.displayStart, NyxLightTokens.displayEnd),
                        start = Offset(0f, 0f),
                        end = Offset(size.width * 0.18f, size.height),
                    ),
                    blendMode = BlendMode.SrcIn,
                )
                val ox = -(originInRoot.x.roundToInt().mod(cellPx)).toFloat()
                val oy = -(originInRoot.y.roundToInt().mod(cellPx)).toFloat()
                translate(ox, oy) {
                    drawRect(
                        brush = gridBrush,
                        topLeft = Offset(-ox, -oy),
                        size = size,
                        blendMode = BlendMode.DstOut,
                    )
                }
                drawContext.canvas.restore()
            },
    ) {
        Text(
            text = text,
            style = style,
            color = Color.White,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Three-cell lit equalizer mark (`3 x 3` cells) for the currently playing row in Nyx. */
@Composable
fun NyxPlayingEqualizer(modifier: Modifier = Modifier) {
    val spacing = LocalNyxSpacing.current
    val gridBrush = rememberNyxGridBrush()
    Canvas(modifier.size(spacing.cell * 3)) {
        val cell = spacing.cellPx
        val heights = floatArrayOf(cell * 2f, cell * 3f, cell)
        val gradient = Brush.verticalGradient(listOf(NyxLightTokens.highlight, NyxLightTokens.meterEnd))
        for (i in heights.indices) {
            val h = heights[i].coerceAtMost(size.height)
            drawRect(gradient, topLeft = Offset(i * cell, size.height - h), size = Size(cell, h))
        }
        drawRect(gridBrush)
    }
}

private fun DrawScope.drawUnderGlow(color: Color, depthPx: Float, alphaScale: Float) {
    val startY = (size.height - depthPx).coerceAtLeast(0f)
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.Transparent,
            0.55f to color.copy(alpha = color.alpha * alphaScale * 0.45f),
            1f to color.copy(alpha = color.alpha * alphaScale),
            startY = startY,
            endY = size.height,
        ),
        topLeft = Offset(0f, startY),
        size = Size(size.width, size.height - startY),
    )
}
