package com.universalmusic.player.platform

import android.util.Log
import com.spotify.Authentication
import com.spotify.connectstate.Connect
import com.universalmusic.player.platform.librespot.LibrespotOAuth
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.gianlu.librespot.android.sink.AndroidSinkOutput
import xyz.gianlu.librespot.audio.MetadataWrapper
import xyz.gianlu.librespot.audio.decoders.AudioQuality
import xyz.gianlu.librespot.audio.decoders.Decoders
import xyz.gianlu.librespot.audio.format.SuperAudioFormat
import xyz.gianlu.librespot.core.Session
import xyz.gianlu.librespot.core.Session.SpotifyAuthenticationException
import xyz.gianlu.librespot.metadata.PlayableId
import xyz.gianlu.librespot.player.Player
import xyz.gianlu.librespot.player.PlayerConfiguration

/**
 * In-process librespot-java Connect receiver for Android. Audio decodes on-device via
 * [AndroidSinkOutput]. Kainos loads tracks and drives play/pause/seek on the player directly;
 * the Web API path in SpotifyProvider is the fallback when that fails.
 */
internal class AndroidLibrespotPlaybackHost(
    private val filesDir: File = androidContext.filesDir,
) : SpotifyWebPlaybackHost {
    private val mutex = Mutex()
    private val directCommands = Mutex()
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<SpotifyWebPlaybackState>(SpotifyWebPlaybackState.Stopped)
    override val state: StateFlow<SpotifyWebPlaybackState> = _state.asStateFlow()
    override val requiresStreamingScope: Boolean = false

    override fun receiverSetup(): SpotifyReceiverSetup =
        if (hasCachedCredentials()) SpotifyReceiverSetup.READY else SpotifyReceiverSetup.NEEDS_SIGN_IN

    private val receiverFlow = MutableSharedFlow<SpotifyReceiverEvent>(extraBufferCapacity = 64)
    private val haltFilter = SpotifyHaltFilter(recoveryScope) { receiverFlow.tryEmit(it) }
    override val receiverEvents = object : SpotifyReceiverEvents {
        override val events = receiverFlow
        override val isAvailable get() = player != null
    }
    @Volatile private var receiverTrackId = ""
    private fun receiverEvent(kind: SpotifyReceiverEvent.Kind, position: Long? = null) {
        haltFilter.accept(SpotifyReceiverEvent(kind, receiverTrackId, position))
    }
    @Volatile override var receiverGeneration: Long = 0L
        private set
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
                    // Kainos owns the queue. A directly loaded track is its own context, which
                    // librespot would otherwise follow with a radio station of its own.
                    .setAutoplayEnabled(false)
                    // Max Connect soft-volume (0–65536). Keep parity with local ExoPlayer at full gain.
                    .setInitialVolume(SPOTIFY_MAX_CONNECT_VOLUME)
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
                Log.i(TAG, "session ready device=$KAINOS_SPOTIFY_ANDROID_DEVICE_NAME")
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

    override suspend fun setVolume(volume: Float): Boolean = mutex.withLock {
        val receiver = player ?: return@withLock false
        receiver.setVolume((volume.coerceIn(0f, 1f) * Player.VOLUME_MAX).toInt())
        true
    }

    override suspend fun playTrack(trackId: String): Boolean {
        val receiver = mutex.withLock {
            ensureStarted(interactive = false, oauthCredentials = null) ?: return false
            player
        } ?: return false
        val uri = "spotify:track:$trackId"
        return runDirect("load $uri") {
            // A fresh player must finish registering with Spotify before it can load.
            receiver.ready().get(RECEIVER_READY_TIMEOUT_S, TimeUnit.SECONDS)
            // Synchronous: resolves the one-track context and starts loading its audio.
            receiver.load(uri, true, false)
            check(receiver.currentPlayable()?.toSpotifyUri() == uri) { "player did not take $uri" }
        }
    }

    override suspend fun pause() = onReceiver("pause") { it.pause() }

    override suspend fun resume() = onReceiver("resume") { it.play() }

    override suspend fun seek(positionMs: Long) =
        onReceiver("seek") { it.seek(positionMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()) }

    private suspend fun onReceiver(action: String, command: (Player) -> Unit): Boolean {
        val receiver = mutex.withLock { player } ?: return false
        return runDirect(action) { command(receiver) }
    }

    /**
     * Player calls block and ignore cancellation, so a skipped load still finishes. Running them
     * one at a time keeps a stale load from landing after the next track's, or after a pause.
     * Failures return false so the caller falls back to the Web API.
     */
    private suspend fun runDirect(action: String, command: () -> Unit): Boolean = try {
        directCommands.withLock { withContext(Dispatchers.IO) { command() } }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        PlaybackTrace.log("Librespot", "direct $action failed, using the Web API: $failure")
        false
    }

    override suspend fun shutdown() = mutex.withLock {
        shutdownLocked()
        _state.value = SpotifyWebPlaybackState.Stopped
    }

    private fun shutdownLocked() {
        haltFilter.reset()
        if (player != null || session != null) {
            Log.i(TAG, "shutdown session")
        }
        val oldPlayer = player
        val oldSession = session
        player = null
        session = null
        runCatching { oldPlayer?.close() }
        runCatching { oldSession?.close() }
    }

    private fun namedDevice() = SpotifyWebPlaybackDevice(deviceName = KAINOS_SPOTIFY_ANDROID_DEVICE_NAME)

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
                    .setDeviceName(KAINOS_SPOTIFY_ANDROID_DEVICE_NAME)
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
                createdSession.addReconnectionListener(object : Session.ReconnectionListener {
                    override fun onConnectionDropped() {
                        installLibrespotReconnectGuard(Thread.currentThread()) { error ->
                            Log.w(TAG, "interrupted AP reconnect; recreating receiver", error)
                            recoveryScope.launch {
                                mutex.withLock {
                                    // A shutdown or a replacement wins over recovery of the old session.
                                    if (session !== createdSession) return@withLock
                                    receiverGeneration++
                                    receiverEvent(SpotifyReceiverEvent.Kind.PAUSED)
                                    shutdownLocked()
                                    ensureStarted(interactive = false, oauthCredentials = null)
                                }
                            }
                        }
                    }
                    override fun onConnectionEstablished() = Unit
                })
                val createdPlayer = Player(playerConf, createdSession)
                createdPlayer.addEventsListener(LibrespotEventsTrace())
                return createdSession to createdPlayer
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

    /**
     * What the in-process Spotify receiver actually did. Kainos drives transport through the
     * Web API, so a pause here that no Engine line asked for came from Spotify's side
     * (another Connect device, a halt while buffering, an inactive session).
     */
    private inner class LibrespotEventsTrace : Player.EventsListener {
        private fun trace(message: String) = PlaybackTrace.log("Librespot", message)

        override fun onContextChanged(player: Player, newUri: String) = trace("context=$newUri")

        override fun onTrackChanged(
            player: Player,
            id: PlayableId,
            metadata: MetadataWrapper?,
            userInitiated: Boolean,
        ) {
            haltFilter.reset()
            receiverTrackId = id.toSpotifyUri().removePrefix("spotify:track:")
            lastHalted = null
            trace("trackChanged ${id.toSpotifyUri()} userInitiated=$userInitiated")
        }

        override fun onPlaybackEnded(player: Player) {
            trace("playbackEnded")
            receiverEvent(SpotifyReceiverEvent.Kind.END_OF_TRACK)
        }

        override fun onPlaybackPaused(player: Player, trackTime: Long) {
            trace("paused at $trackTime")
            receiverEvent(SpotifyReceiverEvent.Kind.PAUSED, trackTime)
        }

        override fun onPlaybackResumed(player: Player, trackTime: Long) {
            trace("resumed at $trackTime")
            receiverEvent(SpotifyReceiverEvent.Kind.PLAYING, trackTime)
        }

        override fun onPlaybackFailed(player: Player, e: Exception) {
            trace("playbackFailed: $e")
            receiverEvent(SpotifyReceiverEvent.Kind.UNAVAILABLE)
        }

        override fun onTrackSeeked(player: Player, trackTime: Long) {
            trace("seeked to $trackTime")
            receiverEvent(SpotifyReceiverEvent.Kind.SEEKED, trackTime)
        }

        override fun onMetadataAvailable(player: Player, metadata: MetadataWrapper) = Unit

        /** Deduplicate before filtering; only reported stalls reach the trace file. */
        @Volatile private var lastHalted: Boolean? = null

        override fun onPlaybackHaltStateChanged(player: Player, halted: Boolean, trackTime: Long) {
            if (halted == lastHalted) return
            lastHalted = halted
            receiverEvent(when {
                player.stateWrapper.state.isPaused -> SpotifyReceiverEvent.Kind.PAUSED
                halted -> SpotifyReceiverEvent.Kind.BUFFERING
                else -> SpotifyReceiverEvent.Kind.PLAYING
            }, trackTime)
        }

        override fun onInactiveSession(player: Player, timeout: Boolean) {
            trace("inactiveSession timeout=$timeout")
            receiverEvent(SpotifyReceiverEvent.Kind.PAUSED)
        }

        override fun onVolumeChanged(player: Player, volume: Float) = Unit

        override fun onPanicState(player: Player) {
            trace("PANIC state")
            receiverEvent(SpotifyReceiverEvent.Kind.UNAVAILABLE)
        }

        override fun onStartedLoading(player: Player) = trace("startedLoading")

        override fun onFinishedLoading(player: Player) {
            trace("finishedLoading")
            receiverEvent(if (player.stateWrapper.state.isPaused) SpotifyReceiverEvent.Kind.PAUSED else SpotifyReceiverEvent.Kind.PLAYING, player.time().toLong())
        }
    }

    private companion object {
        private const val TAG = "KainosSpotify"
        private const val CONNECT_ATTEMPTS = 4
        private const val RETRY_DELAY_MS = 750L
        private const val RECEIVER_READY_TIMEOUT_S = 10L
        /** Spotify client "Loud" preset. */
        private const val SPOTIFY_LOUD_NORMALISATION_PREGAIN_DB = 3f
        /** librespot-java Connect volume scale; 65536 is unity / max. */
        private const val SPOTIFY_MAX_CONNECT_VOLUME = 65_536
        @Volatile
        private var decodersRegistered = false

        private fun registerDecodersOnce() {
            if (decodersRegistered) return
            synchronized(this) {
                if (decodersRegistered) return
                Decoders.registerDecoder(SuperAudioFormat.VORBIS, 0, EndSafeNativeDecoder::class.java)
                Decoders.registerDecoder(SuperAudioFormat.MP3, 0, EndSafeNativeDecoder::class.java)
                decodersRegistered = true
            }
        }
    }
}
