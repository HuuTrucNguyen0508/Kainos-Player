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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.universalmusic.player.ui.theme.LocalNyxSpacing
import com.universalmusic.player.ui.theme.LocalSkinTokens
import com.universalmusic.player.ui.theme.NyxLightTokens
import com.universalmusic.player.ui.theme.Skin
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Desktop sky art has one source pixel per cell. Android uses the grid alone. */
@Composable
internal expect fun rememberNyxSkyBitmap(windowSize: IntSize, cellPx: Int): ImageBitmap?

@Composable
fun isNyx(): Boolean = LocalSkinTokens.current.skin == Skin.NYX

private val LocalNyxGridBrush = staticCompositionLocalOf<ShaderBrush?> { null }

@Composable
internal fun rememberNyxGridBrush(): ShaderBrush =
    LocalNyxGridBrush.current ?: rememberNyxGridBrush(LocalNyxSpacing.current.cellPx.roundToInt())

@Composable
private fun rememberNyxGridBrush(cellPx: Int): ShaderBrush =
    remember(cellPx) {
        val bitmap = ImageBitmap(cellPx, cellPx)
        val drawSize = Size(cellPx.toFloat(), cellPx.toFloat())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, ComposeCanvas(bitmap), drawSize) {
            drawRect(NyxLightTokens.gridLine, Offset.Zero, Size(1f, drawSize.height))
            drawRect(NyxLightTokens.gridLine, Offset(0f, drawSize.height - 1f), Size(drawSize.width, 1f))
        }
        ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated))
    }

@Composable
fun NyxGround(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val cellPx = LocalNyxSpacing.current.cellPx.roundToInt()
    val gridBrush = rememberNyxGridBrush(cellPx)
    var windowSize by remember { mutableStateOf(IntSize.Zero) }
    val bitmap = rememberNyxSkyBitmap(windowSize, cellPx)

    Box(modifier.onSizeChanged { windowSize = it }) {
        Canvas(
            Modifier.matchParentSize().clipToBounds().drawWithCache {
                val cells = IntSize(ceil(size.width / cellPx).toInt(), ceil(size.height / cellPx).toInt())
                val sky = bitmap?.takeIf { cells.width > 0 && cells.height > 0 }?.coverNyxCells(cells)
                onDrawBehind {
                    if (sky == null) {
                        drawRect(NyxLightTokens.gridLine)
                    } else {
                        drawImage(
                            image = sky,
                            dstSize = IntSize(cells.width * cellPx, cells.height * cellPx),
                            filterQuality = FilterQuality.None,
                        )
                        drawRect(gridBrush)
                    }
                }
            },
        ) {}
        CompositionLocalProvider(LocalNyxGridBrush provides gridBrush, content = content)
    }
}

/** Resample only on resize or asset change, so the final nearest-neighbour draw is always ×cellPx. */
private fun ImageBitmap.coverNyxCells(cells: IntSize): ImageBitmap {
    val source = IntSize(width, height)
    if (cells == source) return this
    val cover = maxOf(cells.width.toDouble() / width, cells.height.toDouble() / height)
    val scaled = IntSize(maxOf(cells.width, (width * cover).roundToInt()), maxOf(cells.height, (height * cover).roundToInt()))
    val origin = IntOffset((cells.width - scaled.width) / 2, (cells.height - scaled.height) / 2)
    val bitmap = ImageBitmap(cells.width, cells.height)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, ComposeCanvas(bitmap), Size(cells.width.toFloat(), cells.height.toFloat())) {
        drawImage(this@coverNyxCells, srcSize = source, dstOffset = origin, dstSize = scaled, filterQuality = FilterQuality.None)
    }
    return bitmap
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
    val gridBrush = rememberNyxGridBrush()
    var originInRoot by remember { mutableStateOf(Offset.Zero) }
    val plusPaint = remember { Paint().apply { blendMode = BlendMode.Plus } }

    Box(
        modifier
            .onGloballyPositioned { originInRoot = it.positionInRoot() }
            .drawWithCache {
                val gradient = Brush.linearGradient(
                    listOf(NyxLightTokens.displayStart, NyxLightTokens.displayEnd),
                    start = Offset.Zero,
                    end = Offset(size.width * 0.18f, size.height),
                )
                // Keep full shade throughout the glyph box, with a soft edge in the surrounding sky.
                val shade = Brush.radialGradient(
                    0f to NyxLightTokens.displayShade,
                    0.87f to NyxLightTokens.displayShade,
                    1f to Color.Transparent,
                    center = Offset.Zero,
                    radius = 1f,
                )
                val rect = Rect(Offset.Zero, size)
                onDrawWithContent {
                    translate(center.x, center.y) {
                        scale(size.width * 0.82f, size.height * 0.82f, Offset.Zero) {
                            drawRect(shade, topLeft = Offset(-1f, -1f), size = Size(2f, 2f))
                        }
                    }
                    drawContext.canvas.saveLayer(rect, plusPaint)
                    drawContent()
                    drawRect(gradient, blendMode = BlendMode.SrcIn)
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
                }
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
