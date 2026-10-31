package com.universalmusic.player.platform

import xyz.gianlu.librespot.player.decoders.AndroidNativeDecoder
import xyz.gianlu.librespot.player.decoders.Decoder
import xyz.gianlu.librespot.player.decoders.SeekableInputStream
import java.io.OutputStream

/**
 * AndroidNativeDecoder is final and kills the process at end of stream: it calls
 * signalEndOfInputStream, which needs an input surface it never sets. End the stream instead.
 * librespot constructs this by reflection, so the constructor must stay public.
 */
class EndSafeNativeDecoder(
    audioIn: SeekableInputStream,
    normalizationFactor: Float,
    duration: Int,
) : Decoder(audioIn, normalizationFactor, duration) {
    private val decoder = AndroidNativeDecoder(audioIn, normalizationFactor, duration)

    init {
        setAudioFormat(decoder.audioFormat)
    }

    override fun readInternal(out: OutputStream): Int = try {
        decoder.writeSomeTo(out)
    } catch (failure: IllegalStateException) {
        // Closing races the native decoder's own closed check, so a released codec also ends the stream.
        if (!closed && failure.message?.contains("signalEndOfInputStream") != true) throw failure
        -1
    }

    override fun time(): Int = decoder.time()

    override fun seek(positionMs: Int) = decoder.seek(positionMs)

    override fun close() {
        closed = true
        decoder.close()
    }
}
