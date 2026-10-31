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
            when (policy.change(event, playing)) {
                SpotifyFocusPolicy.Action.PAUSE -> { focusPause = true; AndroidMediaControls.pause() }
                SpotifyFocusPolicy.Action.RESUME -> AndroidMediaControls.play()
                SpotifyFocusPolicy.Action.NONE -> Unit
            }
        }.build()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                policy.userPause()
                AndroidMediaControls.pause()
            }
        }
    }
    fun play(): Boolean {
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
        playing = held
        return held
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
