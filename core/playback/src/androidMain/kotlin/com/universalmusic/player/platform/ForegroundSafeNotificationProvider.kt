package com.universalmusic.player.platform

import android.app.ForegroundServiceStartNotAllowedException
import android.os.Build
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList

/** Media3 1.8's asynchronous artwork update runs outside its service exception guard. */
internal class ForegroundSafeNotificationProvider(
    private val delegate: MediaNotification.Provider,
    private val onDenied: () -> Unit,
) : MediaNotification.Provider by delegate {
    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification = delegate.createNotification(mediaSession, mediaButtonPreferences, actionFactory) { notification ->
        try {
            onNotificationChangedCallback.onNotificationChanged(notification)
        } catch (error: IllegalStateException) {
            if (Build.VERSION.SDK_INT >= 31 && error is ForegroundServiceStartNotAllowedException) onDenied()
            else throw error
        }
    }
}
