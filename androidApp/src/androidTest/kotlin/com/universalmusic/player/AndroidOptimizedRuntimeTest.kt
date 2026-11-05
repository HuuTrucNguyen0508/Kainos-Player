package com.universalmusic.player

import android.content.Context
import android.content.Intent
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.universalmusic.player.platform.SpotifyAuthActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Run with -PandroidTestTarget=optimized; never submits account credentials. */
@RunWith(AndroidJUnit4::class)
class AndroidOptimizedRuntimeTest {
    @Test fun spotifyLoginWebViewOpens() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, SpotifyAuthActivity::class.java).apply {
            putExtra(SpotifyAuthActivity.EXTRA_AUTH_URL, "https://accounts.spotify.com/en/login")
            putExtra(SpotifyAuthActivity.EXTRA_REDIRECT_URI, "http://127.0.0.1:43821/callback")
        }
        ActivityScenario.launch<SpotifyAuthActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                val frame = root.getChildAt(0) as android.view.ViewGroup
                assertNotNull(frame.getChildAt(0) as WebView)
            }
        }
    }
}
