package com.universalmusic.player.platform

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import android.os.Handler
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.universalmusic.player.domain.playback.ReplayGain
import com.universalmusic.player.domain.playback.VolumeLevelling
import java.nio.ByteBuffer
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The gain is a PCM multiply, so a quiet track can be boosted as far as its peak allows: an
 * analysed true peak stops at -1 dBTP, a tagged sample peak at full scale. A tag without a peak
 * gives no such bound, so that track is only ever turned down.
 */
internal fun androidLevellingGainDb(replayGain: ReplayGain): Double =
    VolumeLevelling.appliedGainDb(replayGain, ceilingDb = if (replayGain.peak == null) 0.0 else Double.POSITIVE_INFINITY)

/**
 * Applies each item's ReplayGain to its PCM inside Exo's audio sink: its tags, or else the analysed
 * gain [ItemGainAudioRenderer] attached to the decoded format. The sink is configured with
 * every item's format, tags included, before that item's first sample, and it flushes its
 * processors only once the previous item has fully drained into the output. Taking the tags at
 * [stage] and switching at that flush puts the change on the boundary sample, where a player
 * volume change set on Exo's item transition lands an output latency late. Runs on Exo's playback
 * thread; [enabled] is written from Main.
 */
@OptIn(UnstableApi::class)
internal class LevellingAudioProcessor : BaseAudioProcessor() {
    @Volatile var enabled = true
    @Volatile private var staged: ReplayGain? = null
    private var playing: ReplayGain? = null

    fun stage(format: Format) {
        staged = replayGainOf(format.metadata) ?: (format.customData as? ReplayGain)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioProcessor.AudioFormat.NOT_SET
        }

    override fun onFlush() {
        playing = staged
    }

    override fun onReset() {
        staged = null
        playing = null
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        val gain = playing?.takeIf { enabled }?.let { 10.0.pow(androidLevellingGainDb(it) / 20).toFloat() }
        when {
            gain == null || gain == 1f -> output.put(inputBuffer)
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT -> while (inputBuffer.hasRemaining()) {
                output.putFloat(inputBuffer.float * gain)
            }
            else -> while (inputBuffer.hasRemaining()) {
                output.putShort((inputBuffer.short * gain).roundToInt().coerceIn(-32768, 32767).toShort())
            }
        }
        output.flip()
    }
}

/** Exo's renderers with [levelling] in the audio sink's processing chain. */
@OptIn(UnstableApi::class)
internal class LevellingRenderersFactory(
    context: Context,
    private val levelling: LevellingAudioProcessor,
) : DefaultRenderersFactory(context) {
    /** Swaps Exo's MediaCodec audio renderer for one that stages each item's analysed gain. */
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
        val index = out.indexOfFirst { it.javaClass == MediaCodecAudioRenderer::class.java }
        if (index >= 0) {
            out[index] = ItemGainAudioRenderer(context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback,
                eventHandler, eventListener, audioSink)
        }
    }

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink {
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioOutputPlaybackParams)
            .setAudioProcessors(arrayOf(levelling))
            .build()
        return object : ForwardingAudioSink(sink) {
            override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
                levelling.stage(inputFormat)
                super.configure(inputFormat, specifiedBufferSize, outputChannels)
            }
        }
    }
}
