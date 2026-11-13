package com.universalmusic.player.platform

import android.net.ConnectivityManager
import android.util.Log
import com.spotify.Authentication
import com.spotify.connectstate.Connect
import com.universalmusic.player.platform.librespot.LibrespotOAuth
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    @Volatile private var session: Session? = null
    @Volatile private var player: Player? = null
    /** Set by [installLibrespotStaleTaskGuard] when the current player's audio thread died. */
    @Volatile private var audioSinkDied = false
    /** playTrack reports a failed load itself, so its PANIC must not fail the track first. */
    @Volatile private var loadingDirectly = false
    private val credentialsDir = File(filesDir, "librespot")
    private val credentialsFile = File(credentialsDir, "credentials.json")
    private val connectivity: ConnectivityManager? = androidContext.getSystemService(ConnectivityManager::class.java)
    private val networkWatch = DefaultNetworkWatch(connectivity, recoveryScope, ::onNetworkSwitched)
    private fun network() = connectivity.defaultNetwork()

    /**
     * Kainos's next Spotify track and what this receiver has queued. librespot opens a queued track
     * about 20 s before the current one ends (metadata, audio key over the AP connection, CDN
     * stream) and plays it at the boundary without a load, so a dead AP connection there no longer
     * fails the start. Guarded by [queueLock]: librespot's event thread reads it too.
     */
    private val queueLock = Any()
    @Volatile private var wantedNext: String? = null
    private var queuedOn: Player? = null
    private var queuedId: String? = null
    /**
     * The queued track librespot moved into by itself, before Kainos started it. Its events are
     * held: Kainos still plays the previous track until it adopts this one.
     */
    @Volatile private var awaitingAdoption: String? = null
    /** Adopted before librespot's own track-change event arrived, so that event must not hold it. */
    private var adoptedEarly: String? = null
    private var deferredPause: Job? = null
    /** Set when the default network changed under a receiver that held a track. */
    @Volatile private var rebuildBeforeLoad = false
    /**
     * A fresh player loads only after Spotify registers it. Once registered it can load again even
     * after another device took over, which clears its ready flag until the dealer reconnects.
     */
    @Volatile private var registeredReceiver: Player? = null

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
                val cacheDir = File(filesDir, "librespot-cache")
                val cacheEnabled = runCatching {
                    resetOversizedLibrespotCache(cacheDir)?.let { bytes ->
                        PlaybackTrace.log("Librespot", "reset cache $cacheDir: removed $bytes bytes, including journal")
                    }
                }.onFailure {
                    PlaybackTrace.log("Librespot", "cache limit check failed; disabling cache for this receiver: $it")
                }.isSuccess
                val conf = Session.Configuration.Builder()
                    .setStoreCredentials(true)
                    .setStoredCredentialsFile(credentialsFile)
                    // Raw encrypted CDN chunks, so a replayed track starts from disk.
                    // librespot drops entries unused for 7 days when a session starts.
                    .setCacheEnabled(cacheEnabled)
                    .setCacheDir(cacheDir)
                    .setDoCacheCleanUp(true)
                    // librespot replaces the AP connection when no ping arrives within 120 s plus
                    // this, and Spotify pings every 120 s, so 0 replaced it whenever a ping ran late.
                    .setConnectionTimeout(AP_PING_GRACE_S)
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
                networkWatch.start()
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

    /**
     * While librespot's AP connection is down and it waits to reconnect, every load panics and
     * Spotify answers the Web API transfer with 500, so Kainos skipped track after track (phone
     * traces of 2026-11-05 19:38 and 2026-11-09 10:59). A fresh session connects at once, so a
     * failed load rebuilds the receiver and retries once. A receiver whose audio thread died is
     * rebuilt before it loads. The generation stays the same: the rebuilt receiver loads this track.
     */
    override suspend fun playTrack(trackId: String): Boolean {
        val uri = "spotify:track:$trackId"
        PlaybackTrace.log("Librespot", "playTrack $trackId on ${network()}")
        loadingDirectly = true
        try {
            // Before any rebuild: a queued track that is already open has its audio key.
            if (adoptQueued(trackId)) return true
            val receiver = receiverForLoad(replacing = null) ?: return false
            val loaded = runDirect("load $uri", onFailure = "rebuilding the receiver") { load(receiver, uri) } ||
                receiverForLoad(replacing = receiver)?.let { rebuilt ->
                    runDirect("load $uri on a rebuilt receiver") { load(rebuilt, uri) }
                } == true
            if (loaded) syncQueuedNextLater()
            return loaded
        } finally {
            loadingDirectly = false
        }
    }

    /**
     * Starts [trackId] without a load when this receiver has it queued: librespot either moved into
     * it at the end of the previous track, is about to, or skips to it, using the copy it opened
     * ahead of time. False when it is not queued here or the receiver did not take it.
     */
    private suspend fun adoptQueued(trackId: String): Boolean {
        val receiver = mutex.withLock { player } ?: return false
        val worth = synchronized(queueLock) {
            awaitingAdoption == trackId || (queuedOn === receiver && queuedId == trackId)
        }
        if (!worth) return false
        val started = System.nanoTime()
        var how: String? = null
        val ran = runDirect("adopt $trackId", onFailure = "loading it") { how = adopt(receiver, trackId) }
        val outcome = how
        if (!ran || outcome == null) {
            if (ran) PlaybackTrace.log("Librespot", "adopt failed: the receiver did not take $trackId; loading")
            return false
        }
        PlaybackTrace.log("Librespot", "adopted $outcome to $trackId in ${elapsedMs(started)} ms; no load")
        syncQueuedNextLater()
        return true
    }

    /** Blocking, under [directCommands]. How the receiver reached [trackId], or null to load it. */
    private fun adopt(receiver: Player, trackId: String): String? {
        var waited = false
        var skipped = false
        while (true) {
            // Our fields first: librespot's event thread moves them on together with its own state.
            val (queued, advancedTo) = synchronized(queueLock) { queuedId.takeIf { queuedOn === receiver } to awaitingAdoption }
            val state = receiver.stateWrapper.state
            val step = librespotAdoptStep(
                trackId = trackId,
                current = receiver.currentTrackId(),
                queued = queued,
                advancedTo = advancedTo,
                receiverNext = runCatching { receiver.tracks(false).next.firstOrNull()?.uri }.getOrNull()
                    ?.removePrefix(TRACK_URI_PREFIX),
                playing = state.isPlaying && !state.isPaused,
                positionMs = receiver.time().toLong(),
                durationMs = state.duration,
                waited = waited,
            )
            when (step) {
                LibrespotAdoptStep.LOAD -> return null
                LibrespotAdoptStep.WAIT_FOR_ADVANCE -> {
                    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LIBRESPOT_ADVANCE_WAIT_MS)
                    while (receiver.currentTrackId() != trackId && System.nanoTime() < deadline) Thread.sleep(ADVANCE_POLL_MS)
                    waited = true
                }
                LibrespotAdoptStep.SKIP_TO_QUEUED -> {
                    receiver.next()
                    skipped = true
                    if (receiver.currentTrackId() != trackId) return null
                }
                LibrespotAdoptStep.TAKE_CURRENT -> {
                    synchronized(queueLock) {
                        // A skip's own track-change event is user-initiated and holds nothing.
                        if (awaitingAdoption == trackId) awaitingAdoption = null else if (!skipped) adoptedEarly = trackId
                        if (queuedOn === receiver && queuedId == trackId) queuedId = null
                        deferredPause?.cancel()
                        deferredPause = null
                    }
                    val state = receiver.stateWrapper.state
                    if (state.isPaused || !state.isPlaying) receiver.play()
                    if (receiver.currentTrackId() != trackId || !receiver.stateWrapper.state.isPlaying) return null
                    // A self-advance sends no resume event, and earlier ones were held. Named explicitly:
                    // librespot's own track-change event, which moves receiverTrackId, may still be queued.
                    val kind = if (receiver.stateWrapper.state.isBuffering) SpotifyReceiverEvent.Kind.BUFFERING
                        else SpotifyReceiverEvent.Kind.PLAYING
                    haltFilter.accept(SpotifyReceiverEvent(kind, trackId, receiver.time().toLong()))
                    return if (skipped) "a skip" else "librespot's own advance"
                }
            }
        }
    }

    /**
     * Queues Kainos's next track on this receiver. Asynchronous and serialized with the other
     * player commands; reapplied after every start, because a load clears the queue and an
     * adopted track consumed it.
     */
    override fun queueNextTrack(trackId: String?) {
        wantedNext = trackId
        syncQueuedNextLater()
    }

    private fun syncQueuedNextLater() {
        recoveryScope.launch {
            val receiver = player ?: return@launch
            runDirect("queue the next track", onFailure = "leaving the receiver's queue as it is") { syncQueuedNext(receiver) }
        }
    }

    /** Blocking, under [directCommands]. */
    private fun syncQueuedNext(receiver: Player) {
        // A receiver that never loaded a track has no queue to add to.
        val current = receiver.currentTrackId() ?: return
        if (receiver !== player) return
        val queued = synchronized(queueLock) { queuedId.takeIf { queuedOn === receiver } }
        val edit = planLibrespotQueue(queued, wantedNext, current) ?: return
        edit.remove?.let { removed ->
            receiver.removeFromQueue(TRACK_URI_PREFIX + removed)
            synchronized(queueLock) { if (queuedOn === receiver) queuedId = null }
        }
        edit.add?.let { added ->
            receiver.addToQueue(TRACK_URI_PREFIX + added)
            synchronized(queueLock) {
                queuedOn = receiver
                queuedId = added
            }
        }
        PlaybackTrace.log("Librespot", when {
            edit.add == null -> "cleared queued next ${edit.remove} on the receiver"
            edit.remove == null -> "queued next ${edit.add} on the receiver"
            else -> "queued next ${edit.add} on the receiver, replacing ${edit.remove}"
        })
    }

    private fun Player.currentTrackId(): String? = currentPlayable()?.toSpotifyUri()?.removePrefix(TRACK_URI_PREFIX)

    private suspend fun receiverForLoad(replacing: Player?): Player? = mutex.withLock {
        val reconnecting = session?.reconnecting() == true
        if (player != null && (audioSinkDied || rebuildBeforeLoad || reconnecting || player === replacing)) {
            val reason = when {
                audioSinkDied -> " (its audio thread died)"
                rebuildBeforeLoad -> " (the network changed)"
                reconnecting -> " (its connection to Spotify is down)"
                else -> ""
            }
            PlaybackTrace.log("Librespot", "rebuilding the receiver$reason on ${network()}")
            shutdownLocked()
        }
        ensureStarted(interactive = false, oauthCredentials = null) ?: return@withLock null
        player
    }

    private fun load(receiver: Player, uri: String) {
        if (receiver !== registeredReceiver) {
            receiver.ready().get(RECEIVER_READY_TIMEOUT_S, TimeUnit.SECONDS)
            registeredReceiver = receiver
        }
        // Synchronous: resolves the one-track context and starts loading its audio. On failure
        // librespot logs it, enters its PANIC state and leaves the previous track current.
        receiver.load(uri, true, false)
        synchronized(queueLock) { if (queuedOn === receiver) queuedId = null }
        // A failed reload of the same track keeps its URI current, but a PANIC always stops playing.
        check(receiver.currentPlayable()?.toSpotifyUri() == uri && receiver.stateWrapper.state.isPlaying) {
            "player did not take $uri"
        }
    }

    override suspend fun pause(): Boolean {
        val receiver = mutex.withLock { player } ?: return false
        if (deferPauseOfAdvance(receiver)) return true
        return runDirect("pause") { receiver.pause() }
    }

    /**
     * Kainos pauses the receiver when a track ends and again before every start. Once librespot has
     * moved into the queued next track by itself, that pause would cut into it moments before
     * Kainos adopts it, so it waits [DEFERRED_PAUSE_MS] and applies only if Kainos never did.
     */
    private fun deferPauseOfAdvance(receiver: Player): Boolean {
        val advanced = awaitingAdoption ?: return false
        if (receiver.currentTrackId() != advanced) return false
        return synchronized(queueLock) { deferPauseLocked(receiver, advanced) }
    }

    private fun deferPauseLocked(receiver: Player, advanced: String): Boolean {
        if (awaitingAdoption != advanced) return false
        if (deferredPause?.isActive == true) return true
        PlaybackTrace.log("Librespot", "holding a pause: librespot already plays $advanced, the queued next track")
        deferredPause = recoveryScope.launch {
            delay(DEFERRED_PAUSE_MS)
            runDirect("deferred pause") {
                if (awaitingAdoption == advanced && receiver.currentTrackId() == advanced) {
                    PlaybackTrace.log("Librespot", "Kainos did not start $advanced within $DEFERRED_PAUSE_MS ms; pausing it")
                    receiver.pause()
                }
            }
        }
        return true
    }

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
    private suspend fun runDirect(
        action: String,
        onFailure: String = "using the Web API",
        command: () -> Unit,
    ): Boolean = try {
        directCommands.withLock { withContext(Dispatchers.IO) { command() } }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        PlaybackTrace.log("Librespot", "direct $action failed, $onFailure: $failure")
        false
    }

    override suspend fun shutdown() = mutex.withLock {
        shutdownLocked()
        _state.value = SpotifyWebPlaybackState.Stopped
    }

    private fun shutdownLocked() {
        haltFilter.reset()
        networkWatch.stop()
        rebuildBeforeLoad = false
        synchronized(queueLock) {
            queuedOn = null
            queuedId = null
            awaitingAdoption = null
            adoptedEarly = null
            deferredPause?.cancel()
            deferredPause = null
        }
        registeredReceiver = null
        if (player != null || session != null) {
            Log.i(TAG, "shutdown session")
        }
        val oldPlayer = player
        val oldSession = session
        player = null
        session = null
        audioSinkDied = false
        runCatching { oldPlayer?.close() }
        runCatching { oldSession?.close() }
    }

    /** The new receiver has a new device id and holds no track; the engine sees the old one pause. */
    private suspend fun replaceReceiverLocked() {
        receiverGeneration++
        receiverEvent(SpotifyReceiverEvent.Kind.PAUSED)
        shutdownLocked()
        ensureStarted(interactive = false, oauthCredentials = null)
    }

    /**
     * librespot notices a dead AP connection only on a failed read or a missed ping, minutes after
     * the network changed, and a load meanwhile panics before recovery starts. An idle receiver is
     * replaced now. Replacing one that holds a track would stop it or lose its paused position, so
     * that one is replaced before the next load instead.
     */
    private suspend fun onNetworkSwitched() = mutex.withLock {
        val receiver = player ?: return@withLock
        if (receiver.stateWrapper.state.isPlaying || loadingDirectly || directCommands.isLocked) {
            rebuildBeforeLoad = true
            PlaybackTrace.log("Librespot", "default network changed to ${network()}; rebuilding the receiver before its next load")
            return@withLock
        }
        PlaybackTrace.log("Librespot", "default network changed to ${network()}; rebuilding the idle receiver")
        replaceReceiverLocked()
    }

    /**
     * librespot retries a dropped AP connection every 10 s, and a receiver that holds no track
     * gains nothing by waiting for it, so it is replaced as soon as a validated network is up. A
     * receiver that holds a track keeps streaming its audio over HTTPS and is replaced before its
     * next load instead.
     */
    private suspend fun replaceIdleReceiverWhileReconnecting(dropped: Session) {
        delay(IDLE_RECONNECT_REPLACE_DELAY_MS)
        mutex.withLock {
            if (session !== dropped || !dropped.reconnecting()) return@withLock
            val receiver = player ?: return@withLock
            val network = network()
            if (!network.validated || receiver.currentPlayable() != null || loadingDirectly || directCommands.isLocked) return@withLock
            PlaybackTrace.log("Librespot", "AP connection still down on $network; replacing the idle receiver")
            replaceReceiverLocked()
        }
    }

    /**
     * The connected receiver's session for metadata reads (playlists, radio, track metadata), which
     * go over spclient HTTPS on its existing login instead of a new AP connection. Null while no
     * receiver is up or its AP connection is being replaced. Never waits on [mutex], so a long load
     * or rebuild does not hold metadata back; a caller whose session is closed under it retries.
     * Blocking: call off the main thread.
     */
    internal fun metadataSession(): Session? = session?.takeIf { !it.reconnecting() && it.isValid }

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
            val attemptStarted = System.nanoTime()
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
                val createdSession = builder.create()
                val sessionMs = elapsedMs(attemptStarted)
                val idleReplaceScheduled = AtomicBoolean(false)
                createdSession.addReconnectionListener(object : Session.ReconnectionListener {
                    override fun onConnectionDropped() {
                        PlaybackTrace.log("Librespot", "AP connection dropped on ${network()}; librespot is reconnecting")
                        // librespot calls this on every 10-s retry; one check per session is enough.
                        if (idleReplaceScheduled.compareAndSet(false, true)) {
                            recoveryScope.launch { replaceIdleReceiverWhileReconnecting(createdSession) }
                        }
                        installLibrespotReconnectGuard(Thread.currentThread()) { error ->
                            Log.w(TAG, "interrupted AP reconnect; recreating receiver", error)
                            recoveryScope.launch {
                                mutex.withLock {
                                    // A shutdown or a replacement wins over recovery of the old session.
                                    if (session !== createdSession) return@withLock
                                    replaceReceiverLocked()
                                }
                            }
                        }
                    }
                    override fun onConnectionEstablished() =
                        PlaybackTrace.log("Librespot", "AP connection re-established on ${network()}")
                })
                // Autoplay is off and Kainos loads each track itself, so librespot's own follow-up tasks can fail.
                installLibrespotStaleTaskGuard(onAudioSinkDied = { audioSinkDied = true })
                val playerStarted = System.nanoTime()
                val createdPlayer = Player(playerConf, createdSession)
                createdPlayer.addEventsListener(LibrespotEventsTrace())
                PlaybackTrace.log(
                    "Librespot",
                    "AP connect attempt ${attempt + 1}/$CONNECT_ATTEMPTS: session ${sessionMs} ms, player ${elapsedMs(playerStarted)} ms",
                )
                return createdSession to createdPlayer
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (auth: SpotifyAuthenticationException) {
                throw auth
            } catch (failure: Throwable) {
                lastFailure = failure
                PlaybackTrace.log(
                    "Librespot",
                    "AP connect attempt ${attempt + 1}/$CONNECT_ATTEMPTS failed after ${elapsedMs(attemptStarted)} ms: ${failure.message}",
                )
                if (!isRetryableLibrespotConnectFailure(failure) || attempt == CONNECT_ATTEMPTS - 1) {
                    throw failure
                }
            }
        }
        throw lastFailure ?: IllegalStateException("librespot session creation failed")
    }

    private fun elapsedMs(startedNanos: Long) = (System.nanoTime() - startedNanos) / 1_000_000

    /**
     * What the in-process Spotify receiver actually did. Kainos drives transport through the
     * Web API, so a pause here that no Engine line asked for came from Spotify's side
     * (another Connect device, a halt while buffering, an inactive session).
     */
    private inner class LibrespotEventsTrace : Player.EventsListener {
        private fun trace(message: String) = PlaybackTrace.log("Librespot", message)

        /** A replaced player's queued callbacks must not reach the track its successor plays. */
        private fun Player.isCurrent() = this === this@AndroidLibrespotPlaybackHost.player

        /** Events of a track librespot advanced into wait until Kainos adopts it (see [adopt]). */
        private fun emit(player: Player, kind: SpotifyReceiverEvent.Kind, position: Long? = null) {
            if (player.isCurrent() && awaitingAdoption != receiverTrackId) receiverEvent(kind, position)
        }

        override fun onContextChanged(player: Player, newUri: String) = trace("context=$newUri")

        override fun onTrackChanged(
            player: Player,
            id: PlayableId,
            metadata: MetadataWrapper?,
            userInitiated: Boolean,
        ) {
            trace("trackChanged ${id.toSpotifyUri()} userInitiated=$userInitiated")
            if (!player.isCurrent()) return
            haltFilter.reset()
            val trackId = id.toSpotifyUri().removePrefix(TRACK_URI_PREFIX)
            receiverTrackId = trackId
            lastHalted = null
            synchronized(queueLock) {
                when {
                    userInitiated -> {
                        awaitingAdoption = null
                        adoptedEarly = null
                    }
                    adoptedEarly == trackId -> adoptedEarly = null
                    queuedOn === player && queuedId == trackId -> {
                        queuedId = null
                        awaitingAdoption = trackId
                        trace("librespot advanced to $trackId by itself, the queued next track; holding its events until Kainos starts it")
                    }
                    else -> trace("librespot advanced to $trackId by itself")
                }
            }
        }

        override fun onPlaybackEnded(player: Player) {
            trace("playbackEnded")
            emit(player, SpotifyReceiverEvent.Kind.END_OF_TRACK)
        }

        override fun onPlaybackPaused(player: Player, trackTime: Long) {
            trace("paused at $trackTime")
            emit(player, SpotifyReceiverEvent.Kind.PAUSED, trackTime)
        }

        override fun onPlaybackResumed(player: Player, trackTime: Long) {
            trace("resumed at $trackTime")
            emit(player, SpotifyReceiverEvent.Kind.PLAYING, trackTime)
        }

        override fun onPlaybackFailed(player: Player, e: Exception) {
            trace("playbackFailed: $e")
            emit(player, SpotifyReceiverEvent.Kind.UNAVAILABLE)
        }

        override fun onTrackSeeked(player: Player, trackTime: Long) {
            trace("seeked to $trackTime")
            emit(player, SpotifyReceiverEvent.Kind.SEEKED, trackTime)
        }

        override fun onMetadataAvailable(player: Player, metadata: MetadataWrapper) = Unit

        /** Deduplicate before filtering; only reported stalls reach the trace file. */
        @Volatile private var lastHalted: Boolean? = null

        override fun onPlaybackHaltStateChanged(player: Player, halted: Boolean, trackTime: Long) {
            if (halted == lastHalted) return
            lastHalted = halted
            emit(player, when {
                player.stateWrapper.state.isPaused -> SpotifyReceiverEvent.Kind.PAUSED
                halted -> SpotifyReceiverEvent.Kind.BUFFERING
                else -> SpotifyReceiverEvent.Kind.PLAYING
            }, trackTime)
        }

        override fun onInactiveSession(player: Player, timeout: Boolean) {
            trace("inactiveSession timeout=$timeout")
            emit(player, SpotifyReceiverEvent.Kind.PAUSED)
        }

        override fun onVolumeChanged(player: Player, volume: Float) = Unit

        override fun onPanicState(player: Player) {
            trace("PANIC state")
            if (!loadingDirectly) emit(player, SpotifyReceiverEvent.Kind.UNAVAILABLE)
        }

        override fun onStartedLoading(player: Player) = trace("startedLoading")

        override fun onFinishedLoading(player: Player) {
            trace("finishedLoading")
            emit(player, if (player.stateWrapper.state.isPaused) SpotifyReceiverEvent.Kind.PAUSED else SpotifyReceiverEvent.Kind.PLAYING, player.time().toLong())
        }
    }

    private companion object {
        private const val TAG = "KainosSpotify"
        private const val CONNECT_ATTEMPTS = 4
        private const val RETRY_DELAY_MS = 750L
        private const val RECEIVER_READY_TIMEOUT_S = 10L
        private const val TRACK_URI_PREFIX = "spotify:track:"
        private const val ADVANCE_POLL_MS = 50L
        /** Covers Kainos resolving the next entry between the end of one track and its start. */
        private const val DEFERRED_PAUSE_MS = 3_000L
        private const val IDLE_RECONNECT_REPLACE_DELAY_MS = 2_000L
        private const val AP_PING_GRACE_S = 30
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
                // Vorbis stays on librespot's own decoder. MediaExtractor's Ogg reader scans to the
                // end of the file before the first sample, so the native decoder waited for the
                // whole download: about 8 s on good mobile data and minutes on a weak signal.
                Decoders.registerDecoder(SuperAudioFormat.MP3, 0, EndSafeNativeDecoder::class.java)
                decodersRegistered = true
            }
        }
    }
}
