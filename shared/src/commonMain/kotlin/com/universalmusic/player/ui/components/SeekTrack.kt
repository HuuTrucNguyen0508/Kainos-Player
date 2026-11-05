package com.universalmusic.player.ui.components

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.universalmusic.player.ui.theme.LocalKainosPalette
import com.universalmusic.player.ui.theme.LocalSkinTokens
import com.universalmusic.player.ui.theme.SeekStyle
import kotlin.math.PI
import kotlin.math.sin

/** What the track measures: playback time draws ticks and waves, a level (volume) stays plain. */
enum class SeekKind { Progress, Compact, Level }

private const val WavePeriodMs = 2_000f

/**
 * The skin's seek drawing. Folio rules the line like a ledger: 30 s ticks, minute ticks with serif
 * numerals, ink for the played part and an accent dot at the playhead. Caelestia draws the shell's
 * slider: a wavy primary fill that drifts while playing, a pill handle with 4 dp gaps, and a stop dot.
 * A null [durationMs] on a progress track draws only the quiet rail.
 */
@Composable
fun SeekTrack(
    fraction: Float,
    durationMs: Long?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    scrubbing: Boolean = false,
    kind: SeekKind = SeekKind.Progress,
) {
    val palette = LocalKainosPalette.current
    val tokens = LocalSkinTokens.current
    val scheme = palette.scheme
    val measurer = rememberTextMeasurer()
    val numeralStyle = TextStyle(fontFamily = tokens.display, fontStyle = FontStyle.Italic, fontSize = 10.sp, color = scheme.onSurfaceVariant)
    val waving = tokens.seek == SeekStyle.WAVY && kind != SeekKind.Level && durationMs != null
    var phase by remember { mutableFloatStateOf(0f) }
    if (waving && isPlaying && enabled) {
        LaunchedEffect(Unit) {
            var last = withInfiniteAnimationFrameMillis { it }
            while (true) {
                withInfiniteAnimationFrameMillis { now ->
                    phase = (phase + (now - last) / WavePeriodMs) % 1f
                    last = now
                }
            }
        }
    }
    val alpha = if (enabled) 1f else .38f
    val known = durationMs != null || kind == SeekKind.Level
    val f = fraction.coerceIn(0f, 1f)
    val height = when (kind) {
        SeekKind.Compact -> 12.dp
        SeekKind.Level -> 24.dp
        SeekKind.Progress -> if (tokens.seek == SeekStyle.RULED) 36.dp else 40.dp
    }
    Canvas(modifier.fillMaxWidth().height(height)) {
        when (tokens.seek) {
            SeekStyle.RULED -> {
                val baseY = if (kind == SeekKind.Progress) 12.dp.toPx() else size.height / 2
                drawLine(palette.rule, Offset(0f, baseY), Offset(size.width, baseY), 1.dp.toPx())
                if (!known) return@Canvas
                val ink = scheme.onBackground.copy(alpha = alpha)
                if (kind == SeekKind.Progress && durationMs != null && durationMs > 0) {
                    drawRuledTicks(durationMs, baseY, palette.rule, ink) { minute, x ->
                        val layout = measurer.measure(minute.toString(), numeralStyle)
                        drawText(layout, topLeft = Offset(x - layout.size.width / 2f, baseY + 9.dp.toPx()))
                    }
                }
                val x = f * size.width
                drawLine(ink, Offset(0f, baseY), Offset(x, baseY), (if (kind == SeekKind.Compact) 2f else 2.5f).dp.toPx(), StrokeCap.Butt)
                val dot = if (kind == SeekKind.Level) ink else scheme.primary.copy(alpha = alpha)
                val radius = (if (scrubbing) 6f else if (kind == SeekKind.Compact) 3f else 4.5f).dp.toPx()
                drawCircle(dot, radius, Offset(x, baseY))
            }
            SeekStyle.WAVY -> {
                val stroke = (if (kind == SeekKind.Compact) 3 else 4).dp.toPx()
                val y = size.height / 2
                val rest = scheme.secondaryContainer.copy(alpha = if (enabled) 1f else .5f)
                if (!known) {
                    drawLine(rest, Offset(stroke / 2, y), Offset(size.width - stroke / 2, y), stroke, StrokeCap.Round)
                    return@Canvas
                }
                val active = scheme.primary.copy(alpha = alpha)
                val handleW = (if (kind == SeekKind.Compact) 3 else 4).dp.toPx()
                val handleH = when (kind) {
                    SeekKind.Compact -> 12.dp.toPx(); SeekKind.Level -> 24.dp.toPx(); SeekKind.Progress -> 36.dp.toPx()
                } * if (scrubbing) 1f else .9f
                val gap = 4.dp.toPx()
                val x = f * size.width
                val playedEnd = x - handleW / 2 - gap
                if (playedEnd > stroke / 2) {
                    if (waving) drawWave(active, stroke / 2, playedEnd, y, stroke, phase)
                    else drawLine(active, Offset(stroke / 2, y), Offset(playedEnd, y), stroke, StrokeCap.Round)
                }
                val restStart = x + handleW / 2 + gap
                if (restStart < size.width - stroke / 2) {
                    drawLine(rest, Offset(restStart + stroke / 2, y), Offset(size.width - stroke / 2, y), stroke, StrokeCap.Round)
                }
                drawCircle(active, stroke / 2, Offset(size.width - stroke / 2, y))
                drawRoundRect(active, Offset(x - handleW / 2, y - handleH / 2), Size(handleW, handleH), CornerRadius(handleW / 2))
            }
        }
    }
}

private inline fun DrawScope.drawRuledTicks(
    durationMs: Long,
    baseY: Float,
    rule: Color,
    ink: Color,
    numeral: DrawScope.(minute: Int, x: Float) -> Unit,
) {
    val seconds = durationMs / 1000f
    val pxPerSecond = size.width / seconds
    val step = listOf(30, 60, 300, 600, 1800).first { it * pxPerSecond >= 6.dp.toPx() || it == 1800 }
    val labelEvery = listOf(1, 2, 5, 10, 15, 30, 60).first { it * 60 * pxPerSecond >= 30.dp.toPx() || it == 60 }
    var t = step
    while (t < seconds) {
        val x = t * pxPerSecond
        val minute = t % 60 == 0
        val half = (if (minute) 6 else 3).dp.toPx()
        drawLine(if (minute) ink.copy(alpha = ink.alpha * .7f) else rule, Offset(x, baseY - half), Offset(x, baseY + half), 1.dp.toPx())
        if (minute && (t / 60) % labelEvery == 0) numeral(t / 60, x)
        t += step
    }
}

private fun DrawScope.drawWave(color: Color, start: Float, end: Float, y: Float, stroke: Float, phase: Float) {
    val wavelength = 28.dp.toPx()
    val amplitude = 3.dp.toPx()
    val path = Path()
    var x = start
    val stepPx = 1.5f
    path.moveTo(x, y)
    while (x <= end) {
        val ramp = ((x - start) / wavelength).coerceIn(0f, 1f)
        val offset = sin(((x / wavelength) - phase) * 2 * PI).toFloat() * amplitude * ramp
        path.lineTo(x, y + offset)
        x += stepPx
    }
    drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
}

/**
 * Material [Slider] with the skin's [SeekTrack] in its track slot, so keyboard, focus, drag and
 * accessibility semantics stay Material's own. The thumb is an invisible spacer sized like the
 * drawn handle so the playhead lines up with the touch target.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
    durationMs: Long? = null,
    isPlaying: Boolean = false,
    kind: SeekKind = SeekKind.Progress,
) {
    val interaction = remember { MutableInteractionSource() }
    val dragged by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val palette = LocalKainosPalette.current
    val focusOutline = if (focused) {
        Modifier.border(2.dp, palette.scheme.primary, LocalSkinTokens.current.rounding.full)
    } else Modifier
    val wavy = LocalSkinTokens.current.seek == SeekStyle.WAVY
    val thumb: Dp = if (wavy) 4.dp else 12.dp
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier.then(focusOutline),
        thumb = { Spacer(Modifier.size(thumb, if (kind == SeekKind.Level) 24.dp else 36.dp)) },
        track = { state ->
            val range = state.valueRange
            val span = range.endInclusive - range.start
            SeekTrack(
                fraction = if (span > 0f) (state.value - range.start) / span else 0f,
                durationMs = durationMs,
                isPlaying = isPlaying,
                enabled = enabled,
                scrubbing = dragged || pressed || focused,
                kind = kind,
            )
        },
    )
}

/** The quiet rail on its own, for unknown durations: same footprint as [SkinSlider]. */
@Composable
fun QuietSeekRail(modifier: Modifier = Modifier) {
    SeekTrack(0f, durationMs = null, isPlaying = false, modifier = modifier, enabled = false)
}
