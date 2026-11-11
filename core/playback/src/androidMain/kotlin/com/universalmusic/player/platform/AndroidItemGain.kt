package com.universalmusic.player.platform

import android.content.Context
import android.os.Bundle
import android.os.Handler
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
private const val ALBUM_KEY = "kainos.level.albumKey"

/**
 * [item] carrying the analysed gain of the file at [url], kept in its metadata extras, which
 * survive the trip through the MediaController. With [previous] on the same album the album gain
 * applies, so a gapless join inside an album keeps one level. Files without a measurement, and
 * files whose tags the sink will read, are left as they are.
 */
internal fun MediaItem.withAnalysedGain(loudness: LoudnessLookup, url: String, previous: MediaItem? = null): MediaItem {
    val analysed = loudness.loudnessOf(url) ?: return this
    val gain = VolumeLevelling.choose(tags = null, analysed, previousAlbumKey = previous?.analysedAlbumKey()) ?: return this
    val extras = Bundle(mediaMetadata.extras ?: Bundle.EMPTY).apply {
        putDouble(GAIN_DB, gain.gainDb)
        gain.peak?.let { putDouble(PEAK, it) }
        analysed.albumKey?.let { putString(ALBUM_KEY, it) }
    }
    return buildUpon().setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build()).build()
}

internal fun MediaItem.analysedGain(): ReplayGain? {
    val extras = mediaMetadata.extras ?: return null
    if (!extras.containsKey(GAIN_DB)) return null
    return ReplayGain(extras.getDouble(GAIN_DB), extras.getDouble(PEAK).takeIf { extras.containsKey(PEAK) }, GainSource.ANALYSED)
}

private fun MediaItem.analysedAlbumKey(): String? = mediaMetadata.extras?.getString(ALBUM_KEY)

/**
 * Exo's audio renderer, telling [levelling] the analysed gain of each item as the renderer moves
 * onto that item's stream. That happens on the playback thread before the item's format reaches
 * the sink, so [LevellingAudioProcessor.stage] can fall back to it for a file without tags.
 */
@OptIn(UnstableApi::class)
internal class ItemGainAudioRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioSink: AudioSink,
    private val levelling: LevellingAudioProcessor,
) : MediaCodecAudioRenderer(context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, audioSink) {
    override fun onStreamChanged(
        formats: Array<Format>,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)
        levelling.stageItem(itemOf(mediaPeriodId)?.analysedGain())
    }

    private fun itemOf(id: MediaSource.MediaPeriodId): MediaItem? = runCatching {
        val timeline = timeline
        if (timeline.isEmpty) return@runCatching null
        val period = timeline.getPeriodByUid(id.periodUid, Timeline.Period())
        timeline.getWindow(period.windowIndex, Timeline.Window()).mediaItem
    }.getOrNull()
}
