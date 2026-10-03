package com.universalmusic.player.platform

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.universalmusic.player.domain.model.RepeatMode
import java.io.File
import java.io.IOException

class AndroidPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var forwardingPlayer: KainosForwardingPlayer? = null
    private val favoriteCommand = SessionCommand(COMMAND_TOGGLE_FAVORITE, Bundle.EMPTY)
    private val repeatCommand = SessionCommand(COMMAND_CYCLE_REPEAT, Bundle.EMPTY)
    private var mediaButtonState = MediaButtonState()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        silenceUri = ensureSilenceFile(this)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val exo = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(audioAttributes, true)
            setHandleAudioBecomingNoisy(true)
            addListener(exoTrace)
            addAnalyticsListener(exoAnalyticsTrace)
        }
        val player = KainosForwardingPlayer(exo)
        forwardingPlayer = player
        AndroidMediaControls.attachPlayer(player)
        val session = MediaSession.Builder(this, player)
            .setCallback(sessionTrace)
            .setSessionActivity(createSessionActivity())
            .setMediaButtonPreferences(mediaButtonPreferences(mediaButtonState))
            .build()
        mediaSession = session
        AndroidMediaControls.attachButtonStateListener { favorite, repeat, hasTrack ->
            val next = MediaButtonState(favorite, repeat, hasTrack)
            if (next != mediaButtonState) {
                mediaButtonState = next
                session.setMediaButtonPreferences(mediaButtonPreferences(next))
            }
        }
    }

    private fun trace(message: String) = PlaybackTrace.log("Exo", message)

    /**
     * Raw ExoPlayer view, before [KainosForwardingPlayer] filters events for the Spotify
     * overlay. `playWhenReady` reasons here are the only place audio-focus loss and
     * becoming-noisy pauses are named.
     */
    private val exoTrace = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            trace("playWhenReady=$playWhenReady reason=${playWhenReadyReasonName(reason)}")
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            trace("suppression=${suppressionName(playbackSuppressionReason)}")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            trace("state=${playbackStateName(playbackState)}")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            trace("isPlaying=$isPlaying")
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            trace("mediaItemTransition id=${mediaItem?.mediaId} reason=${mediaItemTransitionReasonName(reason)}")
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            trace(
                "discontinuity ${oldPosition.positionMs}->${newPosition.positionMs} " +
                    "reason=${discontinuityReasonName(reason)}",
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            trace("error=${error.errorCodeName} msg=${error.message} cause=${error.cause}")
        }
    }

    /** Audio-sink level trouble that never surfaces as a state change: underruns, sink/codec errors. */
    private val exoAnalyticsTrace = object : AnalyticsListener {
        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long,
        ) {
            trace("audio underrun bufferSize=$bufferSize bufferMs=$bufferSizeMs sinceLastFeedMs=$elapsedSinceLastFeedMs")
        }

        override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
            trace("audio sink error: $audioSinkError")
        }

        override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) {
            trace("audio codec error: $audioCodecError")
        }

        override fun onAudioTrackInitialized(
            eventTime: AnalyticsListener.EventTime,
            audioTrackConfig: AudioSink.AudioTrackConfig,
        ) {
            trace(
                "audio track init sampleRate=${audioTrackConfig.sampleRate} " +
                    "encoding=${audioTrackConfig.encoding} offload=${audioTrackConfig.offload}",
            )
        }

        override fun onAudioTrackReleased(
            eventTime: AnalyticsListener.EventTime,
            audioTrackConfig: AudioSink.AudioTrackConfig,
        ) {
            trace("audio track released")
        }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean,
        ) {
            trace("load error cancelled=$wasCanceled bytes=${loadEventInfo.bytesLoaded}: $error")
        }
    }

    /** Names which controller (system UI, Bluetooth, our own MediaController) asked for transport changes. */
    private val sessionTrace = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            PlaybackTrace.log("Session3", "controller connected ${controller.describe()}")
            val base = super.onConnect(session, controller)
            if (!base.isAccepted) return base
            val sessionCommands = base.availableSessionCommands.buildUpon()
                .add(favoriteCommand)
                .add(repeatCommand)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(base.availablePlayerCommands)
                .setMediaButtonPreferences(mediaButtonPreferences(mediaButtonState))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val result = when (customCommand.customAction) {
                COMMAND_TOGGLE_FAVORITE -> {
                    PlaybackTrace.log("Session3", "favorite requested by ${controller.describe()}")
                    if (AndroidMediaControls.toggleCurrentFavorite()) {
                        SessionResult.RESULT_SUCCESS
                    } else {
                        SessionResult.RESULT_ERROR_INVALID_STATE
                    }
                }
                COMMAND_CYCLE_REPEAT -> {
                    PlaybackTrace.log("Session3", "repeat requested by ${controller.describe()}")
                    if (AndroidMediaControls.cycleRepeat()) {
                        SessionResult.RESULT_SUCCESS
                    } else {
                        SessionResult.RESULT_ERROR_INVALID_STATE
                    }
                }
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(SessionResult(result))
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            PlaybackTrace.log("Session3", "controller disconnected ${controller.describe()}")
        }

        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int,
        ): Int {
            if (isTransportCommand(playerCommand)) {
                PlaybackTrace.log(
                    "Session3",
                    "${playerCommandName(playerCommand)} requested by ${controller.describe()}",
                )
            }
            return super.onPlayerCommandRequest(session, controller, playerCommand)
        }

        private fun MediaSession.ControllerInfo.describe(): String =
            "$packageName(uid=$uid v=$controllerVersion iv=$interfaceVersion)"
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "onTaskRemoved rootIntent=${rootIntent?.component}")
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.i(TAG, "onBind action=${intent?.action}")
        return super.onBind(intent)
    }

    override fun onDestroy() {
        Log.w(
            TAG,
            "onDestroy session=${mediaSession != null} player=${forwardingPlayer != null}",
        )
        AndroidMediaControls.attachPlayer(null)
        AndroidMediaControls.attachButtonStateListener(null)
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        forwardingPlayer = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KainosPlayback"
        const val EXTRA_OPEN_NOW_PLAYING = "com.universalmusic.player.extra.OPEN_NOW_PLAYING"
        private const val COMMAND_TOGGLE_FAVORITE = "com.universalmusic.player.command.TOGGLE_FAVORITE"
        private const val COMMAND_CYCLE_REPEAT = "com.universalmusic.player.command.CYCLE_REPEAT"
        private const val SESSION_ACTIVITY_REQUEST_CODE = 1201

        @Volatile
        private var silenceUri: Uri? = null

        fun silenceUri(): Uri = silenceUri ?: Uri.EMPTY

        private data class MediaButtonState(
            val favorite: Boolean = false,
            val repeat: RepeatMode = RepeatMode.OFF,
            val hasTrack: Boolean = false,
        )

        private fun mediaButtonPreferences(state: MediaButtonState): List<CommandButton> = listOf(
            CommandButton.Builder(
                if (state.favorite) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED,
            )
                .setDisplayName(if (state.favorite) "Remove from favorites" else "Add to favorites")
                .setSessionCommand(SessionCommand(COMMAND_TOGGLE_FAVORITE, Bundle.EMPTY))
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .setEnabled(state.hasTrack)
                .build(),
            CommandButton.Builder(
                when (state.repeat) {
                    RepeatMode.OFF -> CommandButton.ICON_REPEAT_OFF
                    RepeatMode.ALL -> CommandButton.ICON_REPEAT_ALL
                    RepeatMode.ONE -> CommandButton.ICON_REPEAT_ONE
                },
            )
                .setDisplayName("Repeat ${state.repeat.name.lowercase()}")
                .setSessionCommand(SessionCommand(COMMAND_CYCLE_REPEAT, Bundle.EMPTY))
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .setEnabled(state.hasTrack)
                .build(),
        )

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

    private fun createSessionActivity(): PendingIntent {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent().setClassName(packageName, "$packageName.MainActivity")
        launchIntent
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_NOW_PLAYING, true)
        return PendingIntent.getActivity(
            this,
            SESSION_ACTIVITY_REQUEST_CODE,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
