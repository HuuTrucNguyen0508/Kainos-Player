package com.universalmusic.player

import android.app.ActivityManager
import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.playback.EngineStatus
import com.universalmusic.player.platform.createLocalTrackSource
import com.universalmusic.player.platform.createYouTubeStreamResolver
import com.universalmusic.player.platform.initAndroidPlatform
import com.universalmusic.player.platform.AndroidPlaybackEngine
import com.universalmusic.player.platform.AndroidYouTubeStreamResolver
import com.universalmusic.player.platform.SpotifyPlaybackController
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidPlaybackTest {
    @Test
    fun missingAudioReportsFailureInsteadOfRemainingBuffering() = runBlocking {
        withEngine { engine, context ->
            engine.play(PlaybackHandle.Url(File(context.cacheDir, "missing-audio.wav").toURI().toString()), null)
            awaitStatus(engine, EngineStatus.FAILED)
            assertFalse(engine.state.value.error.isNullOrBlank())
            delay(300)
            assertEquals(EngineStatus.FAILED, engine.state.value.status)
            val recovery = File(context.cacheDir, "kainos-recovery-test.wav")
            recovery.writeBytes(silentWav(2))
            try {
                engine.play(PlaybackHandle.Url(recovery.toURI().toString()), null)
                awaitStatus(engine, EngineStatus.PLAYING)
                assertNull("Old playback error survived a successful new track", engine.state.value.error)
            } finally { recovery.delete() }
        }
    }

    @Test
    fun localAudioPlaysPausesSeeksAndFinishes() = runBlocking {
        withEngine { engine, context ->
            val wav = File(context.cacheDir, "kainos-playback-test.wav")
            wav.writeBytes(silentWav(3))
            try {
                engine.play(PlaybackHandle.Url(wav.toURI().toString()), null)
                awaitStatus(engine, EngineStatus.PLAYING)
                engine.pause()
                awaitStatus(engine, EngineStatus.PAUSED)
                engine.seekTo(1500)
                engine.resume()
                awaitStatus(engine, EngineStatus.PLAYING)
                awaitStatus(engine, EngineStatus.ENDED)
                delay(300)
                assertEquals(EngineStatus.ENDED, engine.state.value.status)
            } finally { wav.delete() }
        }
    }

    @Test
    fun switchingFromSpotifyToLocalAudioPausesSpotify() = runBlocking {
        var pauses = 0
        val spotify = SpotifyPlaybackController({}, { pauses++ }, {}, {})
        withEngine(spotify) { engine, context ->
            val wav = File(context.cacheDir, "kainos-switch-test.wav")
            wav.writeBytes(silentWav(3))
            try {
                engine.play(PlaybackHandle.ProviderPlayback(ProviderId.SPOTIFY, "test-track"), null)
                engine.play(PlaybackHandle.Url(wav.toURI().toString()), null)
                awaitStatus(engine, EngineStatus.PLAYING)
                assertEquals("Remote Spotify was left playing alongside local audio", 1, pauses)
            } finally { wav.delete() }
        }
    }

    @Test
    fun mediaStoreAudioAppearsInLocalLibrary() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        initAndroidPlatform(context)
        grantAudioPermission(context)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "kainos-library-test.wav")
            put(MediaStore.Audio.Media.TITLE, "Kainos library test")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/")
        }
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values))
        try {
            context.contentResolver.openOutputStream(uri)!!.use { it.write(silentWav(1)) }
            val tracks = createLocalTrackSource { emptyList() }.scan()
            assertTrue("Inserted audio is missing from the actual MediaStore scan", tracks.any { it.location == uri.toString() })
        } finally { context.contentResolver.delete(uri, null, null) }
    }

    @Test
    fun playbackSurvivesBackgroundAndRespondsToSystemPause() = runBlocking {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            withEngine { engine, context ->
                val wav = File(context.cacheDir, "kainos-background-test.wav")
                wav.writeBytes(silentWav(10))
                try {
                    engine.play(PlaybackHandle.Url(wav.toURI().toString()), null)
                    awaitStatus(engine, EngineStatus.PLAYING)
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                        addCategory(android.content.Intent.CATEGORY_HOME)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    val before = engine.state.value.positionMs
                    delay(1200)
                    assertEquals(EngineStatus.PLAYING, engine.state.value.status)
                    assertTrue("Playback stopped advancing in background", engine.state.value.positionMs > before)
                    val services = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getRunningServices(100)
                    assertTrue("Audio service is not running in the foreground", services.any {
                        it.service.className == "com.universalmusic.player.platform.AndroidPlaybackService" && it.foreground
                    })
                    automation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL")
                    val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                    val session = manager.getActiveSessions(null).firstOrNull { it.packageName == context.packageName }
                    assertNotNull("Android has no media session for notification/headset controls", session)
                    assertEquals(PlaybackState.STATE_PLAYING, session!!.playbackState!!.state)
                    session.transportControls.pause()
                    awaitStatus(engine, EngineStatus.PAUSED)
                } finally { wav.delete() }
            }
        } finally { automation.dropShellPermissionIdentity() }
    }

    @Test
    fun youtubeStreamResolverIsAvailableAndRejectsBadIds() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        initAndroidPlatform(context)
        val factory = createYouTubeStreamResolver()
        assertTrue("NewPipe YouTube resolver should be available on Android", factory.isAvailable())

        val guarded = AndroidYouTubeStreamResolver(
            ensureInitialized = { true },
            resolveWatchUrl = { error("resolve should not run for invalid ids") },
        )
        assertNull(guarded.resolveAudioUrl(" "))
        assertNull(guarded.resolveAudioUrl("ab c"))
        assertNull(guarded.resolveAudioUrl("id\"quote"))
        assertNull(guarded.resolveAudioUrl("id'quote"))
    }

    @Test
    fun youtubeLiveResolveAndPlaySmoke() = runBlocking {
        // First YouTube video — short, public, stable id for extractor smoke.
        val videoId = "jNQXAC9IVRw"
        withEngine { engine, context ->
            initAndroidPlatform(context)
            val resolver = createYouTubeStreamResolver()
            assertTrue(resolver.isAvailable())
            val resolved = withContext(Dispatchers.IO) { resolver.resolveAudioUrl(videoId) }
            assertNotNull("NewPipe returned no audio URL for $videoId", resolved)
            assertTrue("Resolved URL is not http(s): ${resolved!!.url}", resolved.url.startsWith("http"))

            engine.play(PlaybackHandle.Url(resolved.url), resolved.quality)
            awaitStatus(engine, EngineStatus.PLAYING, attempts = 200)
            val before = engine.state.value.positionMs
            delay(1500)
            assertEquals(
                "Live YouTube audio left PLAYING: ${engine.state.value}",
                EngineStatus.PLAYING,
                engine.state.value.status,
            )
            assertTrue(
                "Live YouTube playback did not advance (pos=$before→${engine.state.value.positionMs})",
                engine.state.value.positionMs > before,
            )
            engine.pause()
            awaitStatus(engine, EngineStatus.PAUSED, attempts = 80)
        }
    }

    private fun grantAudioPermission(context: Context) {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, permission)
    }

    private suspend fun awaitStatus(
        engine: AndroidPlaybackEngine,
        expected: EngineStatus,
        attempts: Int = 100,
    ) {
        repeat(attempts) {
            if (engine.state.value.status == expected) return
            delay(50)
        }
        assertEquals("Playback state did not reach $expected: ${engine.state.value}", expected, engine.state.value.status)
    }

    private suspend fun withEngine(
        spotify: SpotifyPlaybackController = SpotifyPlaybackController({}, {}, {}, {}),
        action: suspend (AndroidPlaybackEngine, Context) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        grantAudioPermission(context)
        val activity = withContext(Dispatchers.IO) { ActivityScenario.launch(MainActivity::class.java) }
        try {
            withContext(Dispatchers.Main) {
                val engine = AndroidPlaybackEngine(context, spotify)
                try { action(engine, context) } finally { engine.stop() }
            }
        } finally { withContext(Dispatchers.IO) { activity.close() } }
    }

    private fun silentWav(seconds: Int): ByteArray {
        val samples = 44100 * seconds
        return ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(44100); putInt(88200)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(samples * 2)
        }.array()
    }
}
