package com.universalmusic.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.universalmusic.player.app.ensureAppContainer
import com.universalmusic.player.platform.initAndroidPlatform
import com.universalmusic.player.ui.UniversalMusicApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class MainActivity : ComponentActivity() {
    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            ensureAppContainer().refreshLocalLibrary()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        initAndroidPlatform(applicationContext)
        val container = ensureAppContainer()
        setContent {
            UniversalMusicApp(container)
        }
        requestLocalMediaPermission()
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
            try {
                val container = ensureAppContainer()
                container.spotify.completeLogin(uri.toString())
                container.refreshSpotifyLibrary()
                Toast.makeText(this@MainActivity, "Spotify connected", Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Toast.makeText(
                    this@MainActivity,
                    failure.message ?: "Spotify connection failed",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun requestLocalMediaPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermission.launch(permission)
        }
    }
}
