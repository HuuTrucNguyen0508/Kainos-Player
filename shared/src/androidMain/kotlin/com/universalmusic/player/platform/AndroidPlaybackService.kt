package com.universalmusic.player.platform

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.io.File

class AndroidPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var forwardingPlayer: KainosForwardingPlayer? = null

    override fun onCreate() {
        super.onCreate()
        silenceUri = ensureSilenceFile(this)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val exo = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(audioAttributes, true)
            setHandleAudioBecomingNoisy(true)
        }
        val player = KainosForwardingPlayer(exo)
        forwardingPlayer = player
        AndroidMediaControls.attachPlayer(player)
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        AndroidMediaControls.attachPlayer(null)
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        forwardingPlayer = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var silenceUri: Uri? = null

        fun silenceUri(): Uri = silenceUri ?: Uri.EMPTY

        private fun ensureSilenceFile(context: Context): Uri {
            val file = File(context.cacheDir, "kainos-session-silence.wav")
            if (!file.exists()) {
                file.writeBytes(silentWavBytes(durationSeconds = 1))
            }
            return Uri.fromFile(file)
        }

        private fun silentWavBytes(durationSeconds: Int): ByteArray {
            val sampleRate = 44_100
            val channels = 1
            val bitsPerSample = 16
            val dataSize = sampleRate * channels * (bitsPerSample / 8) * durationSeconds
            val totalSize = 36 + dataSize
            val buffer = ByteArray(8 + totalSize)
            buffer[0] = 'R'.code.toByte(); buffer[1] = 'I'.code.toByte()
            buffer[2] = 'F'.code.toByte(); buffer[3] = 'F'.code.toByte()
            writeIntLE(buffer, 4, totalSize)
            buffer[8] = 'W'.code.toByte(); buffer[9] = 'A'.code.toByte()
            buffer[10] = 'V'.code.toByte(); buffer[11] = 'E'.code.toByte()
            buffer[12] = 'f'.code.toByte(); buffer[13] = 'm'.code.toByte()
            buffer[14] = 't'.code.toByte(); buffer[15] = ' '.code.toByte()
            writeIntLE(buffer, 16, 16)
            writeShortLE(buffer, 20, 1)
            writeShortLE(buffer, 22, channels)
            writeIntLE(buffer, 24, sampleRate)
            writeIntLE(buffer, 28, sampleRate * channels * bitsPerSample / 8)
            writeShortLE(buffer, 32, channels * bitsPerSample / 8)
            writeShortLE(buffer, 34, bitsPerSample)
            buffer[36] = 'd'.code.toByte(); buffer[37] = 'a'.code.toByte()
            buffer[38] = 't'.code.toByte(); buffer[39] = 'a'.code.toByte()
            writeIntLE(buffer, 40, dataSize)
            return buffer
        }

        private fun writeIntLE(buffer: ByteArray, offset: Int, value: Int) {
            buffer[offset] = (value and 0xff).toByte()
            buffer[offset + 1] = ((value shr 8) and 0xff).toByte()
            buffer[offset + 2] = ((value shr 16) and 0xff).toByte()
            buffer[offset + 3] = ((value shr 24) and 0xff).toByte()
        }

        private fun writeShortLE(buffer: ByteArray, offset: Int, value: Int) {
            buffer[offset] = (value and 0xff).toByte()
            buffer[offset + 1] = ((value shr 8) and 0xff).toByte()
        }
    }
}
