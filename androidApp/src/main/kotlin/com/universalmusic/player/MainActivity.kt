package com.universalmusic.player

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.universalmusic.player.app.ensureAppContainer
import com.universalmusic.player.platform.initAndroidPlatform
import com.universalmusic.player.ui.UniversalMusicApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        initAndroidPlatform(applicationContext)
        val container = ensureAppContainer()
        setContent {
            UniversalMusicApp(container)
        }
        handleSpotifyCallback(intent)
    }

    // launchMode is singleTask: when the app is already running, the OAuth redirect
    // arrives here instead of onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSpotifyCallback(intent)
    }

    private fun handleSpotifyCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "universalmusic" || uri.host != "spotify-callback") return
        lifecycleScope.launch {
            runCatching { ensureAppContainer().spotify.completeLogin(uri.toString()) }
        }
    }
}
