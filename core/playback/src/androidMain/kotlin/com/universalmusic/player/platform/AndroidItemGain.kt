package com.universalmusic.player.platform

import android.content.Context
import android.os.Bundle
import android.os.Handler
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import com.universalmusic.player.domain.playback.GainSource
import com.universalmusic.player.domain.playback.LoudnessLookup
import com.universalmusic.player.domain.playback.ReplayGain
import com.universalmusic.player.domain.playback.VolumeLevelling

private const val GAIN_DB = "kainos.level.gainDb"
private const val PEAK = "kainos.level.peak"

/** Carries analysed gain through the MediaController; file tags still win in the sink. */
internal fun MediaItem.withAnalysedGain(loudness: LoudnessLookup, url: String): MediaItem {
    val analysed = loudness.loudnessOf(url) ?: return this
    val gain = VolumeLevelling.choose(tags = null, analysed) ?: return this
    val extras = Bundle(mediaMetadata.extras ?: Bundle.EMPTY).apply {
        putDouble(GAIN_DB, gain.gainDb)
        gain.peak?.let { putDouble(PEAK, it) }
    }
    return buildUpon().setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build()).build()
}

internal fun MediaItem.analysedGain(): ReplayGain? {
    val extras = mediaMetadata.extras ?: return null
    if (!extras.containsKey(GAIN_DB)) return null
    return ReplayGain(extras.getDouble(GAIN_DB), extras.getDouble(PEAK).takeIf { extras.containsKey(PEAK) }, GainSource.ANALYSED)
}

/** Carries each input item's gain with its decoded output format, through codec lookahead. */
@OptIn(UnstableApi::class)
internal class ItemGainAudioRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioSink: AudioSink,
) : MediaCodecAudioRenderer(context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, audioSink) {
    private var inputGain: ReplayGain? = null

    override fun onStreamChanged(
        formats: Array<Format>,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        inputGain = itemOf(mediaPeriodId)?.analysedGain()
        super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)
    }

    override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
        // The decoder queues this Format with its samples. MediaCodecAudioRenderer copies
        // customData into the sink format only when those samples reach the output.
        formatHolder.format = formatHolder.format?.buildUpon()?.setCustomData(inputGain)?.build()
        return super.onInputFormatChanged(formatHolder)
    }

    private fun itemOf(id: MediaSource.MediaPeriodId): MediaItem? = runCatching {
        val timeline = timeline
        if (timeline.isEmpty) return@runCatching null
        val period = timeline.getPeriodByUid(id.periodUid, Timeline.Period())
        timeline.getWindow(period.windowIndex, Timeline.Window()).mediaItem
    }.getOrNull()
}
