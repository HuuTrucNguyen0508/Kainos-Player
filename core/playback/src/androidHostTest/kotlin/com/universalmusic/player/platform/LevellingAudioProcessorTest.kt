package com.universalmusic.player.platform

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class LevellingAudioProcessorTest {
    private val processor = LevellingAudioProcessor()
    private val pcm16 = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)

    private fun format(vararg tags: VorbisComment) =
        Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setMetadata(Metadata(*tags)).build()

    /** -13 dB with peak 0.1 levels to -6 dB, which is a factor of 0.501187. */
    private val tagged = format(VorbisComment("REPLAYGAIN_TRACK_GAIN", "-13.00 dB"), VorbisComment("REPLAYGAIN_TRACK_PEAK", "0.100000"))
    private val untagged = format(VorbisComment("TITLE", "Next"))

    private fun process(vararg samples: Short): ShortArray {
        val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach(input::putShort)
        input.flip()
        processor.queueInput(input)
        val output = processor.output.asShortBuffer()
        return ShortArray(output.remaining()).also(output::get)
    }

    /** What DefaultAudioSink does: configure the format, then flush once the previous stream drained. */
    private fun startStream(format: Format) {
        processor.stage(format)
        processor.configure(pcm16)
        processor.flush()
    }

    @Test fun appliesTheTrackGainToEverySample() {
        startStream(tagged)
        assertArrayEquals(shortArrayOf(5012, -5012, 16422, -16423), process(10_000, -10_000, 32_767, -32_768))
    }

    @Test fun theOutgoingItemKeepsItsGainUntilTheSinkSwitchesStreams() {
        startStream(tagged)
        // The sink is told about the next item before the outgoing one has drained.
        processor.stage(untagged)
        processor.configure(pcm16)
        assertArrayEquals("Outgoing tail, still levelled", shortArrayOf(5012), process(10_000))
        processor.flush()
        assertArrayEquals("First sample of the next item, at its own level", shortArrayOf(10_000), process(10_000))
    }

    @Test fun offPlaysAtTheFileLevel() {
        startStream(tagged)
        processor.enabled = false
        assertArrayEquals(shortArrayOf(10_000), process(10_000))
    }

    @Test fun aSeekKeepsTheGainOfTheItemPlaying() {
        startStream(tagged)
        processor.flush()
        assertArrayEquals(shortArrayOf(5012), process(10_000))
    }
    @Test fun delayedOutputFormatsCarryTheirOwnGainAcrossLookaheadSkipAndSeek() {
        val first = untagged.buildUpon().setCustomData(com.universalmusic.player.domain.playback.ReplayGain(-13.0, 0.1)).build()
        val next = untagged.buildUpon().setCustomData(com.universalmusic.player.domain.playback.ReplayGain(-7.0, 0.1)).build()
        // The decoder can read the next item before the previous format reaches the sink.
        startStream(first)
        assertArrayEquals(shortArrayOf(5012), process(10_000))
        processor.stage(next)
        processor.configure(pcm16)
        assertArrayEquals(shortArrayOf(5012), process(10_000))
        processor.flush()
        assertArrayEquals(shortArrayOf(10_000), process(10_000))
        // A skip discards the pending stream; a seek keeps this output format's gain.
        startStream(first)
        processor.flush()
        assertArrayEquals(shortArrayOf(5012), process(10_000))
        startStream(untagged)
        assertArrayEquals(shortArrayOf(10_000), process(10_000))
    }

}
