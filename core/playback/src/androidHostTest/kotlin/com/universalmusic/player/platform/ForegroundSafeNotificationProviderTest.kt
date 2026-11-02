package com.universalmusic.player.platform

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.content.Context
import android.os.Bundle
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.google.common.collect.ImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ForegroundSafeNotificationProviderTest {
    @Test fun delayedArtworkPromotionDenialPausesInsteadOfCrashing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val player = ExoPlayer.Builder(context).build()
        val session = MediaSession.Builder(context, player).build()
        try {
            val notification = MediaNotification(1, Notification.Builder(context, "test").build())
            lateinit var artworkReady: MediaNotification.Provider.Callback
            val delegate = object : MediaNotification.Provider {
                override fun createNotification(session: MediaSession, buttons: ImmutableList<CommandButton>, actions: MediaNotification.ActionFactory, callback: MediaNotification.Provider.Callback): MediaNotification {
                    artworkReady = callback
                    return notification
                }
                override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle) = false
            }
            var pauses = 0
            val safe = ForegroundSafeNotificationProvider(delegate) { pauses++ }
            // The action factory is unused by this delegate. Use Media3's actual factory.
            val factoryClass = Class.forName("androidx.media3.session.DefaultActionFactory")
            val constructor = factoryClass.declaredConstructors.first().apply { isAccessible = true }
            val actions = constructor.newInstance(org.robolectric.Robolectric.buildService(AndroidPlaybackService::class.java).get()) as MediaNotification.ActionFactory
            var failure: RuntimeException = ForegroundServiceStartNotAllowedException("background")
            safe.createNotification(session, ImmutableList.of(), actions) { throw failure }
            artworkReady.onNotificationChanged(notification)
            assertEquals(1, pauses)
            failure = IllegalStateException("unrelated")
            assertThrows(IllegalStateException::class.java) { artworkReady.onNotificationChanged(notification) }
        } finally {
            session.release()
            player.release()
        }
    }
}
