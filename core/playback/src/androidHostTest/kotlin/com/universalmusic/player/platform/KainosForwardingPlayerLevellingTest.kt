package com.universalmusic.player.platform

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.test.core.app.ApplicationProvider
import com.google.common.collect.ImmutableList
import com.universalmusic.player.domain.playback.Levelling
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KainosForwardingPlayerLevellingTest {
    private val exo = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
    private val player = KainosForwardingPlayer(exo)
    private val reported = mutableListOf<Levelling?>()

    init {
        player.onLevellingChanged = { reported += it }
        player.volume = 0.8f
    }

    @After fun release() = exo.release()

    private fun tracks(mime: String, vararg tags: Metadata.Entry) = Tracks(ImmutableList.of(Tracks.Group(
        TrackGroup(Format.Builder().setSampleMimeType(mime).setMetadata(Metadata(*tags)).build()),
        false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true),
    )))

    private fun flac(gain: String, peak: String) = tracks(MimeTypes.AUDIO_FLAC,
        VorbisComment("REPLAYGAIN_TRACK_GAIN", gain), VorbisComment("replaygain_track_peak", peak))

    @Test fun flacVorbisCommentsTurnTheTrackDown() {
        player.onAudioTracks(flac("-13.00 dB", "0.100000"))
        assertEquals(Levelling.Applied(-6.0), player.levelling)
        assertEquals(0.8f * 0.501187f, exo.volume, 0.0001f)
        assertEquals("Controllers still see the volume they set", 0.8f, player.volume)
    }

    @Test fun mp3TxxxGainIsCappedAtUnityBecauseExoCannotBoost() {
        player.onAudioTracks(tracks(MimeTypes.AUDIO_MPEG,
            TextInformationFrame("TXXX", "REPLAYGAIN_TRACK_GAIN", ImmutableList.of("+3.00 dB")),
            TextInformationFrame("TXXX", "REPLAYGAIN_TRACK_PEAK", ImmutableList.of("0.500000"))))
        assertEquals(Levelling.Applied(0.0), player.levelling)
        assertEquals(0.8f, exo.volume, 0.0001f)
    }

    @Test fun mp4ItunesFreeformAtomIsRead() {
        player.onAudioTracks(tracks(MimeTypes.AUDIO_AAC,
            InternalFrame("com.apple.iTunes", "replaygain_track_gain", "-10.50 dB")))
        assertEquals(Levelling.Applied(-3.5), player.levelling)
    }

    @Test fun untaggedPlaysAtTheUserVolume() {
        player.onAudioTracks(tracks(MimeTypes.AUDIO_FLAC, VorbisComment("TITLE", "Song")))
        assertEquals(Levelling.Untagged, player.levelling)
        assertEquals(0.8f, exo.volume, 0.0001f)
    }

    @Test fun switchingOffRestoresTheUserVolumeForTheTrackPlayingNow() {
        player.onAudioTracks(flac("-13.00 dB", "0.100000"))
        player.setLevelling(false)
        assertEquals(0.8f, exo.volume, 0.0001f)
        player.setLevelling(true)
        player.volume = 0.5f
        assertEquals(0.5f * 0.501187f, exo.volume, 0.0001f)
        assertEquals(listOf(Levelling.Applied(-6.0), Levelling.Off, Levelling.Applied(-6.0)), reported)
    }

    @Test fun nothingIsClaimedWhileTheNextItemLoads() {
        player.onAudioTracks(flac("-13.00 dB", "0.100000"))
        player.onAudioTracks(Tracks.EMPTY)
        assertEquals(null, player.levelling)
        assertEquals(0.8f, exo.volume, 0.0001f)
    }
}
