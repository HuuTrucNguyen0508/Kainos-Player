package com.universalmusic.player.platform

import android.util.Log
import com.spotify.Authentication
import com.spotify.connectstate.Connect
import com.universalmusic.player.platform.librespot.LibrespotOAuth
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.gianlu.librespot.android.sink.AndroidSinkOutput
import xyz.gianlu.librespot.audio.decoders.AudioQuality
import xyz.gianlu.librespot.audio.decoders.Decoders
import xyz.gianlu.librespot.audio.format.SuperAudioFormat
import xyz.gianlu.librespot.core.Session
import xyz.gianlu.librespot.core.Session.SpotifyAuthenticationException
import xyz.gianlu.librespot.player.Player
import xyz.gianlu.librespot.player.PlayerConfiguration
import xyz.gianlu.librespot.player.decoders.AndroidNativeDecoder

/**
 * In-process librespot-java Connect receiver for Android. Audio decodes on-device via
 * [AndroidSinkOutput]; Kainos still drives play/pause/seek through the Web API.
 */
internal class AndroidLibrespotPlaybackHost(
    private val filesDir: File = androidContext.filesDir,
) : SpotifyWebPlaybackHost {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<SpotifyWebPlaybackState>(SpotifyWebPlaybackState.Stopped)
    override val state: StateFlow<SpotifyWebPlaybackState> = _state.asStateFlow()
    override val requiresStreamingScope: Boolean = false

    private var session: Session? = null
    private var player: Player? = null
    private val credentialsDir = File(filesDir, "librespot")
    private val credentialsFile = File(credentialsDir, "credentials.json")

    override suspend fun ensureDeviceReady(): SpotifyWebPlaybackDevice? = mutex.withLock {
        Log.i(TAG, "ensureDeviceReady interactive=false hasCredentials=${hasCachedCredentials()}")
        ensureStarted(interactive = false, oauthCredentials = null)
    }

    override suspend fun prepareAuthentication(): SpotifyWebPlaybackDevice? {
        val needsOAuth: Boolean
        mutex.withLock {
            shutdownLocked()
            val rejected = (_state.value as? SpotifyWebPlaybackState.Failed)
                ?.reason
                .let { it as? SpotifyWebPlaybackFailure.LibrespotExited }
                ?.detail
                ?.contains("rejected", ignoreCase = true) == true
            if (rejected) {
                runCatching { credentialsFile.delete() }
            }
            needsOAuth = !hasCachedCredentials()
        }

        val oauthCredentials = if (needsOAuth) {
            withContext(Dispatchers.IO) {
                LibrespotOAuth.login()
            }
        } else {
            null
        }

        return mutex.withLock {
            ensureStarted(interactive = true, oauthCredentials = oauthCredentials)
        }
    }

    private suspend fun ensureStarted(
        interactive: Boolean,
        oauthCredentials: Authentication.LoginCredentials?,
    ): SpotifyWebPlaybackDevice? {
        if (session != null && player != null) {
            _state.value = SpotifyWebPlaybackState.ConnectingSpotify
            return namedDevice()
        }
        shutdownLocked()

        if (!interactive && !hasCachedCredentials()) {
            _state.value = SpotifyWebPlaybackState.Failed(SpotifyWebPlaybackFailure.LibrespotAuthenticationRequired)
            return null
        }

        _state.value = SpotifyWebPlaybackState.StartingHost
        Log.i(TAG, "starting librespot session interactive=$interactive oauth=${oauthCredentials != null}")
        return withContext(Dispatchers.IO) {
            try {
                registerDecodersOnce()
                credentialsDir.mkdirs()
                val conf = Session.Configuration.Builder()
                    .setStoreCredentials(true)
                    .setStoredCredentialsFile(credentialsFile)
                    .setCacheEnabled(false)
                    .build()
                val playerConf = PlayerConfiguration.Builder()
                    .setOutput(PlayerConfiguration.AudioOutput.CUSTOM)
                    .setOutputClass(AndroidSinkOutput::class.java.name)
                    .setPreferredQuality(AudioQuality.VERY_HIGH)
                    // Match Spotify's Loud volume normalisation (+3 dB pregain).
                    .setEnableNormalisation(true)
                    .setNormalisationPregain(SPOTIFY_LOUD_NORMALISATION_PREGAIN_DB)
                    .build()
                val (createdSession, createdPlayer) = createSessionWithRetry(
                    conf = conf,
                    oauthCredentials = oauthCredentials,
                    playerConf = playerConf,
                ) ?: return@withContext null
                session = createdSession
                player = createdPlayer
                _state.value = SpotifyWebPlaybackState.ConnectingSpotify
                Log.i(TAG, "session ready device=$KAINOS_SPOTIFY_DEVICE_NAME")
                namedDevice()
            } catch (cancelled: CancellationException) {
                Log.w(TAG, "session start cancelled")
                throw cancelled
            } catch (failure: SpotifyAuthenticationException) {
                Log.e(TAG, "auth rejected: ${failure.message}")
                shutdownLocked()
                runCatching { credentialsFile.delete() }
                _state.value = SpotifyWebPlaybackState.Failed(
                    SpotifyWebPlaybackFailure.LibrespotExited(
                        failure.message?.takeIf { it.isNotBlank() }
                            ?: "Spotify rejected the librespot credentials. Set up in-app playback again in Settings.",
                    ),
                )
                null
            } catch (failure: Throwable) {
                Log.e(TAG, "session failed: ${failure.message}", failure)
                shutdownLocked()
                _state.value = SpotifyWebPlaybackState.Failed(
                    SpotifyWebPlaybackFailure.LibrespotExited(
                        failure.message?.takeIf { it.isNotBlank() }
                            ?: failure::class.java.simpleName,
                    ),
                )
                null
            }
        }
    }

    override suspend fun shutdown() = mutex.withLock {
        shutdownLocked()
        _state.value = SpotifyWebPlaybackState.Stopped
    }

    private fun shutdownLocked() {
        if (player != null || session != null) {
            Log.i(TAG, "shutdown session")
        }
        runCatching { player?.close() }
        runCatching { session?.close() }
        player = null
        session = null
    }

    private fun namedDevice() = SpotifyWebPlaybackDevice(deviceName = KAINOS_SPOTIFY_DEVICE_NAME)

    private fun hasCachedCredentials(): Boolean =
        credentialsFile.isFile && credentialsFile.length() > 0L

    private fun createSessionWithRetry(
        conf: Session.Configuration,
        oauthCredentials: Authentication.LoginCredentials?,
        playerConf: PlayerConfiguration,
    ): Pair<Session, Player>? {
        var lastFailure: Throwable? = null
        repeat(CONNECT_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                Thread.sleep(RETRY_DELAY_MS * attempt)
            }
            try {
                val builder = Session.Builder(conf)
                    .setPreferredLocale(Locale.getDefault().language)
                    .setDeviceType(Connect.DeviceType.SMARTPHONE)
                    .setDeviceName(KAINOS_SPOTIFY_DEVICE_NAME)
                when {
                    oauthCredentials != null -> builder.credentials(oauthCredentials)
                    hasCachedCredentials() -> builder.stored()
                    else -> {
                        _state.value = SpotifyWebPlaybackState.Failed(
                            SpotifyWebPlaybackFailure.LibrespotAuthenticationRequired,
                        )
                        return null
                    }
                }
                Log.i(TAG, "AP connect attempt ${attempt + 1}/$CONNECT_ATTEMPTS")
                val createdSession = builder.create()
                Log.i(TAG, "AP connected")
                return createdSession to Player(playerConf, createdSession)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (auth: SpotifyAuthenticationException) {
                throw auth
            } catch (failure: Throwable) {
                lastFailure = failure
                Log.w(TAG, "AP connect attempt ${attempt + 1} failed: ${failure.message}")
                if (!isRetryableLibrespotConnectFailure(failure) || attempt == CONNECT_ATTEMPTS - 1) {
                    throw failure
                }
            }
        }
        throw lastFailure ?: IllegalStateException("librespot session creation failed")
    }

    private companion object {
        private const val TAG = "KainosSpotify"
        private const val CONNECT_ATTEMPTS = 4
        private const val RETRY_DELAY_MS = 750L
        /** Spotify client "Loud" preset. */
        private const val SPOTIFY_LOUD_NORMALISATION_PREGAIN_DB = 3f
        @Volatile
        private var decodersRegistered = false

        private fun registerDecodersOnce() {
            if (decodersRegistered) return
            synchronized(this) {
                if (decodersRegistered) return
                Decoders.registerDecoder(SuperAudioFormat.VORBIS, 0, AndroidNativeDecoder::class.java)
                Decoders.registerDecoder(SuperAudioFormat.MP3, 0, AndroidNativeDecoder::class.java)
                decodersRegistered = true
            }
        }
    }
}
