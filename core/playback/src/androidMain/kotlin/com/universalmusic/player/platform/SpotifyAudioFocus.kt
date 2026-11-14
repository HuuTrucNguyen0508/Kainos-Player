package com.universalmusic.player.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.universalmusic.player.domain.playback.SpotifyFocusPolicy

/** Called only on Main, after ExoPlayer has relinquished focus. */
internal class SpotifyAudioFocus(private val context: Context) {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val policy = SpotifyFocusPolicy()
    private var held = false
    private var registered = false
    private var focusPause = false
    private var playing = false
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener { change ->
            val event = when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> SpotifyFocusPolicy.Change.LOSS
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> SpotifyFocusPolicy.Change.TRANSIENT_LOSS
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> SpotifyFocusPolicy.Change.DUCK
                else -> SpotifyFocusPolicy.Change.GAIN
            }
            if (event == SpotifyFocusPolicy.Change.LOSS) held = false
            val action = policy.change(event, playing)
            val context = if (event == SpotifyFocusPolicy.Change.GAIN) "" else " (${audioContext()})"
            PlaybackTrace.log("Engine", "Spotify audio focus $event -> $action$context")
            when (action) {
                SpotifyFocusPolicy.Action.PAUSE -> { focusPause = true; AndroidMediaControls.pause() }
                SpotifyFocusPolicy.Action.RESUME -> AndroidMediaControls.play()
                SpotifyFocusPolicy.Action.NONE -> Unit
            }
        }.build()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                PlaybackTrace.log("Engine", "audio output became noisy (headphones or Bluetooth disconnected); pausing Spotify")
                policy.userPause()
                AndroidMediaControls.pause()
            }
        }
    }
    /**
     * What else was making sound when focus went, for traces: Android names no package, but the
     * mode tells a call from a ringtone and the active players' usages tell navigation, an
     * assistant or a notification apart.
     */
    private fun audioContext(): String = runCatching {
        val players = manager.activePlaybackConfigurations.joinToString(", ") { config ->
            val attributes = config.audioAttributes
            "${usageName(attributes.usage)}/${contentTypeName(attributes.contentType)}"
        }.ifEmpty { "none" }
        "mode=${modeName(manager.mode)} musicActive=${manager.isMusicActive} players=[$players]"
    }.getOrElse { "audio state unavailable: $it" }

    /** A refusal must not fail the track: HyperOS can refuse a re-request, and nothing here can retry it. */
    fun play() {
        policy.activate()
        if (!registered) {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            }
            registered = true
        }
        if (!held) held = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!held) PlaybackTrace.log("Engine", "Spotify audio focus refused")
        playing = true
    }
    fun pause() {
        playing = false
        if (focusPause) { focusPause = false; return }
        release()
    }
    fun release() {
        policy.deactivate()
        playing = false
        if (held) manager.abandonAudioFocusRequest(request)
        held = false
        if (registered) { context.unregisterReceiver(noisy); registered = false }
    }
}

private fun modeName(mode: Int): String = when (mode) {
    AudioManager.MODE_NORMAL -> "normal"
    AudioManager.MODE_RINGTONE -> "ringtone"
    AudioManager.MODE_IN_CALL -> "in_call"
    AudioManager.MODE_IN_COMMUNICATION -> "in_communication"
    AudioManager.MODE_CALL_SCREENING -> "call_screening"
    else -> "mode$mode"
}

private fun usageName(usage: Int): String = when (usage) {
    AudioAttributes.USAGE_MEDIA -> "media"
    AudioAttributes.USAGE_GAME -> "game"
    AudioAttributes.USAGE_VOICE_COMMUNICATION -> "voice_call"
    AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING -> "call_signalling"
    AudioAttributes.USAGE_ALARM -> "alarm"
    AudioAttributes.USAGE_NOTIFICATION -> "notification"
    AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> "ringtone"
    AudioAttributes.USAGE_NOTIFICATION_EVENT -> "notification_event"
    AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY -> "accessibility"
    AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "navigation"
    AudioAttributes.USAGE_ASSISTANCE_SONIFICATION -> "sonification"
    AudioAttributes.USAGE_ASSISTANT -> "assistant"
    AudioAttributes.USAGE_UNKNOWN -> "unknown"
    else -> "usage$usage"
}

private fun contentTypeName(contentType: Int): String = when (contentType) {
    AudioAttributes.CONTENT_TYPE_MUSIC -> "music"
    AudioAttributes.CONTENT_TYPE_SPEECH -> "speech"
    AudioAttributes.CONTENT_TYPE_MOVIE -> "movie"
    AudioAttributes.CONTENT_TYPE_SONIFICATION -> "sonification"
    AudioAttributes.CONTENT_TYPE_UNKNOWN -> "unknown"
    else -> "content$contentType"
}
