package com.universalmusic.player.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.compose.asPainter
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * "Album light": the Now Playing signature. The cover's own color glows softly behind the
 * artwork and fades into the selected scheme's background before the controls, so every
 * Appearance scheme stays in charge of text and surfaces.
 */

private const val SAMPLE_PX = 24
private const val CACHE_LIMIT = 256

/** Cover tint per artwork URL; tiny, so recent tracks and the mini player reuse it without decoding. */
private object ArtworkTintCache {
    private val tints = LinkedHashMap<String, Color>()

    fun get(url: String): Color? = tints[url]

    fun put(url: String, color: Color) {
        tints[url] = color
        if (tints.size > CACHE_LIMIT) tints.remove(tints.keys.first())
    }
}

/** The cover's characteristic color, or null while unknown or when there is no artwork. */
@Composable
fun rememberArtworkTint(artworkUrl: String?): Color? {
    val context = LocalPlatformContext.current
    var tint by remember(artworkUrl) { mutableStateOf(artworkUrl?.let(ArtworkTintCache::get)) }
    LaunchedEffect(artworkUrl) {
        val url = artworkUrl ?: return@LaunchedEffect
        if (tint != null) return@LaunchedEffect
        val color = try {
            // Same shared loader and caches as ArtworkImage, so this never refetches from the network.
            val request = ImageRequest.Builder(context).data(url).size(SAMPLE_PX).build()
            val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult
                ?: return@LaunchedEffect
            val painter = result.image.asPainter(context)
            withContext(Dispatchers.Default) { characteristicColor(painter) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return@LaunchedEffect
        ArtworkTintCache.put(url, color)
        tint = color
    }
    return tint
}

/**
 * The glow color for the current scheme: the cover tint (or the scheme accent when there is no
 * art), with saturation and lightness clamped so it reads as light, not paint, on dark and light
 * schemes alike. Animated so track changes cross-fade.
 */
@Composable
fun rememberAlbumLight(artworkUrl: String?): State<Color> {
    val scheme = MaterialTheme.colorScheme
    val tint = rememberArtworkTint(artworkUrl)
    val target = remember(tint, scheme) { albumLightColor(tint ?: scheme.primary, scheme) }
    return animateColorAsState(target, animationSpec = tween(durationMillis = 700), label = "albumLight")
}

/** Soft pool of [light] centered behind the cover at ([centerX], [centerY]) fractions of the area. */
fun Modifier.albumLight(
    light: Color,
    darkScheme: Boolean,
    centerX: Float = 0.5f,
    centerY: Float = 0.3f,
): Modifier = drawBehind {
    val radius = max(size.width, size.height) * 0.75f
    drawRect(
        Brush.radialGradient(
            0f to light.copy(alpha = if (darkScheme) 0.55f else 0.45f),
            0.5f to light.copy(alpha = if (darkScheme) 0.20f else 0.16f),
            1f to Color.Transparent,
            center = Offset(size.width * centerX, size.height * centerY),
            radius = radius,
        ),
    )
}

/** Horizontal wash for the mini player: the light sits under the cover and fades out across the bar. */
fun Modifier.albumLightStrip(light: Color, darkScheme: Boolean): Modifier = drawBehind {
    drawRect(
        Brush.horizontalGradient(
            0f to light.copy(alpha = if (darkScheme) 0.30f else 0.24f),
            0.6f to Color.Transparent,
        ),
    )
}

internal fun albumLightColor(source: Color, scheme: ColorScheme): Color {
    val dark = scheme.isDark
    val (h, s, _) = source.toHsl()
    // Near-grey covers keep a hint of the scheme accent instead of glowing flat grey.
    val hue = if (s < 0.12f) scheme.primary.toHsl().first else h
    // Floor high enough that warm covers read as color, not beige; cap keeps it light, not paint.
    val saturation = s.coerceIn(0.42f, 0.68f)
    val lightness = if (dark) 0.50f else 0.70f
    return hslColor(hue, saturation, lightness)
}

/** Weighted average favoring vivid, mid-bright pixels so a cover's accent wins over its background. */
internal fun characteristicColor(painter: Painter): Color? {
    val bitmap = ImageBitmap(SAMPLE_PX, SAMPLE_PX)
    CanvasDrawScope().draw(
        Density(1f),
        LayoutDirection.Ltr,
        Canvas(bitmap),
        Size(SAMPLE_PX.toFloat(), SAMPLE_PX.toFloat()),
    ) {
        with(painter) { draw(size) }
    }
    val pixels = bitmap.toPixelMap()
    var r = 0.0
    var g = 0.0
    var b = 0.0
    var total = 0.0
    for (x in 0 until pixels.width) {
        for (y in 0 until pixels.height) {
            val c = pixels[x, y]
            if (c.alpha < 0.5f) continue
            val hi = max(c.red, max(c.green, c.blue))
            val lo = min(c.red, min(c.green, c.blue))
            val saturation = if (hi == 0f) 0f else (hi - lo) / hi
            // Mid-brightness weighting keeps near-black and blown-out pixels from dominating.
            val brightness = 1f - abs(hi - 0.6f)
            val weight = 0.05 + saturation * saturation * brightness
            r += c.red * weight
            g += c.green * weight
            b += c.blue * weight
            total += weight
        }
    }
    if (total == 0.0) return null
    return Color((r / total).toFloat(), (g / total).toFloat(), (b / total).toFloat())
}

private fun Color.relativeLuminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** True for the dark variant of whichever Appearance scheme is active. */
val ColorScheme.isDark: Boolean get() = background.relativeLuminance() < 0.5f

/** (hue 0-360, saturation 0-1, lightness 0-1) */
internal fun Color.toHsl(): Triple<Float, Float, Float> {
    val hi = max(red, max(green, blue))
    val lo = min(red, min(green, blue))
    val lightness = (hi + lo) / 2f
    if (hi == lo) return Triple(0f, 0f, lightness)
    val delta = hi - lo
    val saturation = delta / (1f - abs(2f * lightness - 1f))
    val hue = when (hi) {
        red -> 60f * (((green - blue) / delta) % 6f)
        green -> 60f * (((blue - red) / delta) + 2f)
        else -> 60f * (((red - green) / delta) + 4f)
    }
    return Triple((hue + 360f) % 360f, saturation.coerceIn(0f, 1f), lightness)
}

internal fun hslColor(hue: Float, saturation: Float, lightness: Float): Color {
    val c = (1f - abs(2f * lightness - 1f)) * saturation
    val x = c * (1f - abs((hue / 60f) % 2f - 1f))
    val m = lightness - c / 2f
    val (r, g, b) = when {
        hue < 60f -> Triple(c, x, 0f)
        hue < 120f -> Triple(x, c, 0f)
        hue < 180f -> Triple(0f, c, x)
        hue < 240f -> Triple(0f, x, c)
        hue < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color((r + m).coerceIn(0f, 1f), (g + m).coerceIn(0f, 1f), (b + m).coerceIn(0f, 1f))
}
