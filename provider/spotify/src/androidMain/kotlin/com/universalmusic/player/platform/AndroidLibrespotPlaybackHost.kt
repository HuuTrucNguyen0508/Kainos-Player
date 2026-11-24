package com.universalmusic.player.platform

import android.net.ConnectivityManager
import android.telephony.TelephonyManager
import android.util.Log
import com.spotify.Authentication
import com.spotify.connectstate.Connect
import com.universalmusic.player.platform.librespot.LibrespotOAuth
import java.io.File
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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
    private val haltFilter = SpotifyHaltFilter(recoveryScope) { event ->
        receiverFlow.tryEmit(event)
        watchPlayback(event)
    }
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

    /**
     * Kainos asked this receiver to play [kainosTrackId] and has not paused it since: only then may
     * a stream that broke under it be reloaded. Every Kainos command moves [transportEpoch], so a
     * recovery decided before one never loads after it.
     */
    @Volatile private var wantsPlay = false
    @Volatile private var kainosTrackId: String? = null
    private val transportEpoch = AtomicLong()
    private val clock = LibrespotPositionClock(System::currentTimeMillis)
    /** When the current stall was reported; null while the track is not stalled. */
    @Volatile private var stalledSinceMs: Long? = null
    /** One recovery check at a time; a later one waits and then judges the receiver afresh. */
    private val recoveryTurn = Mutex()
    /** Reloads of [kainosTrackId]; a new track or the listener's own Play starts again at 0. */
    @Volatile private var recoveryAttempts = 0
    /** Recovery gave up on a receiver that may still hold the track but cannot play it. */
    @Volatile private var reloadOnResume = false
    /** The track a recovery has reloaded (or tried to) since Kainos last started one. */
    @Volatile private var recoveryTrack: String? = null
    /** Pauses Kainos sent to [ownPauseReceiver] that librespot has not acknowledged yet. */
    private val ownPauseLock = Any()
    private var ownPauseReceiver: Player? = null
    private var ownPausesPending = 0
    private val credentialsDir = File(filesDir, "librespot")
    private val credentialsFile = File(credentialsDir, "credentials.json")
    private val connectivity: ConnectivityManager? = androidContext.getSystemService(ConnectivityManager::class.java)
    private val networkWatch = DefaultNetworkWatch(connectivity, recoveryScope, ::onNetworkSwitched) {
        checkApAddress("the network's addresses changed")
    }
    private val radioWatch = CellularRadioWatch(androidContext.getSystemService(TelephonyManager::class.java)) { from, to ->
        PlaybackTrace.log("Librespot", "mobile radio changed from $from to $to")
        checkApAddress("the radio changed from $from to $to")
    }
    private fun network() = connectivity.defaultNetwork(radioWatch.label)

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
                radioWatch.start()
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
    override suspend fun playTrack(trackId: String, positionMs: Long): Boolean {
        val uri = "spotify:track:$trackId"
        val at = if (positionMs > 0) " at $positionMs ms" else ""
        PlaybackTrace.log("Librespot", "playTrack $trackId$at on ${network()}")
        beginTransport(wantsPlay = false)
        if (kainosTrackId != trackId) recoveryAttempts = 0
        kainosTrackId = trackId
        reloadOnResume = false
        recoveryTrack = null
        clock.anchor(positionMs, playing = false)
        loadingDirectly = true
        try {
            // Before any rebuild: a queued track that is already open has its audio key.
            if (positionMs <= 0 && adoptQueued(trackId)) {
                wantsPlay = true
                return true
            }
            val receiver = receiverForLoad(replacing = null) ?: return false
            val loaded = runDirect("load $uri$at", onFailure = "rebuilding the receiver") { load(receiver, uri, positionMs) } ||
                receiverForLoad(replacing = receiver)?.let { rebuilt ->
                    runDirect("load $uri$at on a rebuilt receiver") { load(rebuilt, uri, positionMs) }
                } == true
            if (loaded) syncQueuedNextLater()
            wantsPlay = loaded
            return loaded
        } finally {
            loadingDirectly = false
        }
    }

    private fun beginTransport(wantsPlay: Boolean) {
        transportEpoch.incrementAndGet()
        this.wantsPlay = wantsPlay
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
        var how = ""
        if (!runDirect("adopt of $trackId", onFailure = "loading it") { how = adopt(receiver, trackId) }) return false
        PlaybackTrace.log("Librespot", "adopted $how to $trackId in ${elapsedMs(started)} ms; no load")
        syncQueuedNextLater()
        return true
    }

    /** Blocking, under [directCommands]. How the receiver reached [trackId]; throws why it did not. */
    private fun adopt(receiver: Player, trackId: String): String {
        var waited = false
        var skipped = false
        while (true) {
            // Our fields first: librespot's event thread moves them on together with its own state.
            val (queued, advancedTo) = synchronized(queueLock) { queuedId.takeIf { queuedOn === receiver } to awaitingAdoption }
            val state = receiver.stateWrapper.state
            val current = receiver.currentTrackId()
            val receiverNext = runCatching { receiver.tracks(false).next.firstOrNull()?.uri }.getOrNull()
                ?.removePrefix(TRACK_URI_PREFIX)
            val step = librespotAdoptStep(
                trackId = trackId,
                current = current,
                queued = queued,
                advancedTo = advancedTo,
                receiverNext = receiverNext,
                playing = state.isPlaying && !state.isPaused,
                positionMs = receiver.time().toLong(),
                durationMs = state.duration,
                waited = waited,
                connectionDown = session?.reconnecting() == true,
            )
            when (step) {
                LibrespotAdoptStep.LOAD -> error(
                    "the receiver is on $current with $receiverNext next" +
                        if (session?.reconnecting() == true) " and its connection to Spotify is down" else "",
                )
                LibrespotAdoptStep.WAIT_FOR_ADVANCE -> {
                    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LIBRESPOT_ADVANCE_WAIT_MS)
                    while (receiver.currentTrackId() != trackId && System.nanoTime() < deadline) Thread.sleep(ADVANCE_POLL_MS)
                    waited = true
                }
                LibrespotAdoptStep.SKIP_TO_QUEUED -> {
                    receiver.next()
                    skipped = true
                    check(receiver.currentTrackId() == trackId) { "the skip landed on ${receiver.currentTrackId()}" }
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
                    check(receiver.currentTrackId() == trackId && receiver.stateWrapper.state.isPlaying) {
                        "the receiver did not play it (on ${receiver.currentTrackId()})"
                    }
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
        wantedNext = trackId?.removePrefix(TRACK_URI_PREFIX)
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
        val unwanted = selfAdvancedTrack(receiver)?.takeIf { it != wantedNext }
        if (unwanted != null && synchronized(queueLock) { deferredPause?.isActive == true }) {
            synchronized(queueLock) {
                deferredPause?.cancel()
                deferredPause = null
            }
            PlaybackTrace.log("Librespot", "Kainos moved on from $unwanted, which librespot advanced into; pausing it")
            pauseFromKainos(receiver)
        }
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

    private fun load(receiver: Player, uri: String, positionMs: Long = 0) {
        if (receiver !== registeredReceiver) {
            receiver.ready().get(RECEIVER_READY_TIMEOUT_S, TimeUnit.SECONDS)
            registeredReceiver = receiver
        }
        // Synchronous: resolves the one-track context and starts loading its audio. On failure
        // librespot logs it, enters its PANIC state and leaves the previous track current.
        receiver.load(uri, true, false)
        // librespot holds a seek made while the track opens and starts decoding there.
        if (positionMs > 0) receiver.seek(positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        synchronized(queueLock) { if (queuedOn === receiver) queuedId = null }
        // A failed reload of the same track keeps its URI current, but a PANIC always stops playing.
        check(receiver.currentPlayable()?.toSpotifyUri() == uri && receiver.stateWrapper.state.isPlaying) {
            "player did not take $uri"
        }
    }

    /** The listener, audio focus or a stop: never held, and it drops a held transition pause. */
    override suspend fun pause(): Boolean {
        beginTransport(wantsPlay = false)
        val receiver = mutex.withLock { player } ?: return false
        synchronized(queueLock) {
            deferredPause?.cancel()
            deferredPause = null
        }
        return runDirect("pause") { pauseFromKainos(receiver) }
    }

    override suspend fun pauseForTransition(): Boolean {
        beginTransport(wantsPlay = false)
        val receiver = mutex.withLock { player } ?: return false
        if (deferPauseOfAdvance(receiver)) return true
        return runDirect("pause") { pauseFromKainos(receiver) }
    }

    /**
     * Kainos pauses the receiver when a track ends and again before every start ([pauseForTransition]). Once librespot has
     * moved into the queued next track by itself, that pause would cut into it moments before
     * Kainos adopts it, so it waits [DEFERRED_PAUSE_MS] and applies only if Kainos never did. Only
     * while Kainos still wants that track next; [syncQueuedNext] pauses it once Kainos moves on.
     */
    private fun deferPauseOfAdvance(receiver: Player): Boolean {
        val advanced = selfAdvancedTrack(receiver)?.takeIf { it == wantedNext } ?: return false
        synchronized(queueLock) {
            if (deferredPause?.isActive == true) return true
            PlaybackTrace.log("Librespot", "holding a pause: librespot already plays $advanced, the queued next track")
            deferredPause = recoveryScope.launch {
                delay(DEFERRED_PAUSE_MS)
                runDirect("deferred pause") {
                    if (selfAdvancedTrack(receiver) == advanced) {
                        PlaybackTrace.log("Librespot", "Kainos did not start $advanced within $DEFERRED_PAUSE_MS ms; pausing it")
                        pauseFromKainos(receiver)
                    }
                }
            }
        }
        return true
    }

    /**
     * The queued track [receiver] moved into and Kainos has not adopted; null otherwise. Matches the
     * queued id too, because librespot's own track-change event can arrive after the advance.
     */
    private fun selfAdvancedTrack(receiver: Player): String? {
        val current = receiver.currentTrackId() ?: return null
        return current.takeIf {
            synchronized(queueLock) { awaitingAdoption == current || (queuedOn === receiver && queuedId == current) }
        }
    }

    /**
     * The listener's Play. A receiver that lost the track, whose connection is down, or that sat
     * through a network change cannot just continue, so the track loads again where it stopped.
     */
    override suspend fun resume(): Boolean {
        beginTransport(wantsPlay = false)
        recoveryAttempts = 0
        val receiver = mutex.withLock { player }
        val trackId = kainosTrackId
        val stale = receiver == null || reloadOnResume || rebuildBeforeLoad || session?.reconnecting() == true ||
            runCatching { receiver.currentTrackId() }.getOrNull() != trackId
        if (receiver == null && trackId == null) return false
        if (trackId != null && stale) {
            PlaybackTrace.log("Librespot", "resume reloads $trackId: the receiver cannot continue it")
            return playTrack(trackId, clock.positionMs())
        }
        return runDirect("resume") { checkNotNull(receiver).play() }.also { wantsPlay = it }
    }

    /** Also moves a recovery in progress, which loads at the clock's position, so a seek never cancels it. */
    override suspend fun seek(positionMs: Long): Boolean {
        clock.moveTo(positionMs.coerceAtLeast(0))
        return onReceiver("seek") { it.seek(positionMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()) }
    }

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
        beginTransport(wantsPlay = false)
        kainosTrackId = null
        shutdownLocked()
        _state.value = SpotifyWebPlaybackState.Stopped
    }

    private fun shutdownLocked() {
        haltFilter.reset()
        stalledSinceMs = null
        networkWatch.stop()
        radioWatch.stop()
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
        // Also cancels a recovery reload in flight: the engine is told this receiver paused.
        beginTransport(wantsPlay = false)
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
     * A handover, such as 5G to 4G, can take away the address the AP connection was opened from
     * while Android keeps the same network. Nothing reaches that socket again, and librespot would
     * only notice when Spotify's next ping (every 2 minutes) fails to arrive. Closing the socket
     * makes librespot's packet reader fail now and run librespot's own reconnect, which stays the
     * only one: its first attempt is interrupted by librespot itself, so the AP is back on the
     * 10-s retry, still well before the ping.
     */
    private fun checkApAddress(reason: String) {
        val watched = session ?: return
        recoveryScope.launch {
            val socket = LibrespotApSocket.of(watched) ?: return@launch
            val local = socket.localAddress
            if (socket.isClosed || !socket.isConnected || local == null || local.isAnyLocalAddress) return@launch
            if (addressStillOnDevice(local)) return@launch
            if (session !== watched) return@launch
            PlaybackTrace.log(
                "Librespot",
                "AP connection's address is gone after $reason on ${network()}; closing it so librespot reconnects",
            )
            runCatching { socket.close() }
        }
    }

    /**
     * For the drop trace: whether the dropped connection's own address, as recorded when it
     * connected (a closed socket no longer reports it), had already left the phone.
     */
    private fun apAddressState(local: InetAddress?): String = when {
        local == null || local.isAnyLocalAddress -> "address unknown"
        addressStillOnDevice(local) -> "address kept"
        else -> "address gone"
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

    /** Follows the receiver's reported state: where its track is, and since when it has stalled. */
    private fun watchPlayback(event: SpotifyReceiverEvent) {
        if (event.trackId != kainosTrackId) return
        val position = event.positionMs
        when (event.kind) {
            SpotifyReceiverEvent.Kind.SEEKED, SpotifyReceiverEvent.Kind.POSITION_CORRECTION ->
                position?.let(clock::moveTo)
            SpotifyReceiverEvent.Kind.BUFFERING -> {
                position?.let { clock.anchor(it, playing = false) } ?: clock.stop()
                if (stalledSinceMs == null) {
                    stalledSinceMs = System.currentTimeMillis()
                    scheduleRecoveryCheck(
                        "a stall of ${LIBRESPOT_STALL_RECOVERY_MS / 1_000} s",
                        LIBRESPOT_STALL_RECOVERY_MS,
                    )
                }
            }
            else -> {
                stalledSinceMs = null
                val playing = event.kind == SpotifyReceiverEvent.Kind.PLAYING
                position?.let { clock.anchor(it, playing) } ?: clock.stop()
            }
        }
    }

    private fun scheduleRecoveryCheck(reason: String, afterMs: Long) {
        val expected = player ?: return
        recoveryScope.launch {
            delay(afterMs)
            recoverIfBroken(reason, expected)
        }
    }

    /**
     * Reloads Kainos's track where it stopped when the stream under it broke: a VPN switch closes
     * the AP connection and the audio stream together, and librespot then ends the song early or
     * stalls on it for good. [force] reloads a receiver that has to be replaced anyway. Checks run
     * one at a time, each judging the receiver afresh, so a later one never skips an earlier one's
     * work. Returns whether it reloaded or left the track paused.
     */
    private suspend fun recoverIfBroken(reason: String, expected: Player, force: Boolean = false): Boolean =
        recoveryTurn.withLock {
            val waitingSince = System.currentTimeMillis()
            while (player === expected) {
                val epoch = transportEpoch.get()
                val trackId = kainosTrackId
                val state = runCatching { expected.stateWrapper.state }.getOrNull() ?: return@withLock false
                val step = librespotRecovery(
                    wanted = wantsPlay,
                    trackId = trackId,
                    receiverTrack = runCatching { expected.currentTrackId() }.getOrNull(),
                    playing = state.isPlaying && !state.isPaused,
                    stalledForMs = stalledSinceMs?.let { System.currentTimeMillis() - it },
                    positionMs = clock.positionMs(),
                    durationMs = state.duration.takeIf { it > 0 },
                    networkValidated = network().validated,
                    attempts = recoveryAttempts,
                    force = force,
                )
                when (step) {
                    LibrespotRecovery.None -> return@withLock false
                    LibrespotRecovery.GiveUp ->
                        return@withLock giveUpRecovery(checkNotNull(trackId), epoch, "$LIBRESPOT_RECOVERY_ATTEMPTS reloads failed")
                    LibrespotRecovery.WaitForNetwork -> {
                        if (System.currentTimeMillis() - waitingSince >= RECOVERY_NETWORK_WAIT_MS) {
                            return@withLock giveUpRecovery(
                                checkNotNull(trackId), epoch, "no validated network within ${RECOVERY_NETWORK_WAIT_MS / 1_000} s",
                            )
                        }
                        delay(RECOVERY_NETWORK_POLL_MS)
                    }
                    is LibrespotRecovery.Reload ->
                        return@withLock reloadWithRetries(checkNotNull(trackId), step.positionMs, expected, epoch, reason)
                }
            }
            false
        }

    /**
     * Up to [LIBRESPOT_RECOVERY_ATTEMPTS] reloads of [trackId], each on a fresh receiver. A receiver
     * that could not be built counts as a failed attempt; the track and position do not depend on
     * one. Any Kainos command since [epoch] ends it, since the listener has moved on.
     */
    private suspend fun reloadWithRetries(trackId: String, positionMs: Long, broken: Player, epoch: Long, reason: String): Boolean {
        var replacing: Player? = broken
        recoveryTrack = trackId
        clock.anchor(positionMs, playing = false)
        while (recoveryAttempts < LIBRESPOT_RECOVERY_ATTEMPTS) {
            recoveryAttempts++
            PlaybackTrace.log(
                "Librespot",
                "recovering $trackId at $positionMs ms after $reason on ${network()} " +
                    "(attempt $recoveryAttempts/$LIBRESPOT_RECOVERY_ATTEMPTS)",
            )
            when (reloadOnce(trackId, replacing, epoch)) {
                ReloadOutcome.RELOADED -> {
                    PlaybackTrace.log("Librespot", "recovered $trackId on ${network()}")
                    syncQueuedNextLater()
                    return true
                }
                ReloadOutcome.SUPERSEDED -> return false
                ReloadOutcome.FAILED -> Unit
            }
            replacing = player
            val retryAt = System.currentTimeMillis() + RECOVERY_RETRY_MS
            val networkBy = retryAt + RECOVERY_NETWORK_WAIT_MS
            while (System.currentTimeMillis() < retryAt || !network().validated) {
                if (transportEpoch.get() != epoch) return false
                if (System.currentTimeMillis() >= networkBy) {
                    return giveUpRecovery(trackId, epoch, "no validated network within ${RECOVERY_NETWORK_WAIT_MS / 1_000} s")
                }
                delay(RECOVERY_NETWORK_POLL_MS)
            }
        }
        return giveUpRecovery(trackId, epoch, "$LIBRESPOT_RECOVERY_ATTEMPTS reloads failed")
    }

    private enum class ReloadOutcome { RELOADED, SUPERSEDED, FAILED }

    /**
     * Closes the broken receiver and loads into a fresh one. Closing and loading each take the
     * transport turn ([directCommands]) and check [epoch] inside it, so a track Kainos started or
     * paused since then is never closed or overwritten. The build in between only holds [mutex],
     * like playTrack's own rebuild: connecting to Spotify can take a while on a bad network, and
     * Kainos's commands that only need the turn must not wait for it.
     */
    private suspend fun reloadOnce(trackId: String, replacing: Player?, epoch: Long): ReloadOutcome {
        directCommands.withLock {
            mutex.withLock {
                if (transportEpoch.get() != epoch) return ReloadOutcome.SUPERSEDED
                if (replacing != null && player === replacing) shutdownLocked()
            }
        }
        val receiver = mutex.withLock {
            if (transportEpoch.get() != epoch) return ReloadOutcome.SUPERSEDED
            ensureStarted(interactive = false, oauthCredentials = null) ?: return ReloadOutcome.FAILED
            player ?: return ReloadOutcome.FAILED
        }
        return directCommands.withLock {
            withContext(Dispatchers.IO) {
                if (transportEpoch.get() != epoch || player !== receiver) return@withContext ReloadOutcome.SUPERSEDED
                loadingDirectly = true
                try {
                    // A seek during the rebuild moved the clock; nothing else moves it while no receiver plays.
                    load(receiver, TRACK_URI_PREFIX + trackId, clock.positionMs())
                    ReloadOutcome.RELOADED
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    PlaybackTrace.log("Librespot", "recovery load of $trackId failed: $failure")
                    ReloadOutcome.FAILED
                } finally {
                    loadingDirectly = false
                }
            }
        }
    }

    /**
     * Leaves the track paused where it stopped, so the listener's Play loads it again. Inside the
     * transport turn and only while no Kainos command came since [epoch]. Returns whether it did.
     */
    private suspend fun giveUpRecovery(trackId: String, epoch: Long, why: String): Boolean =
        directCommands.withLock {
            withContext(Dispatchers.IO) {
                if (transportEpoch.get() != epoch) return@withContext false
                val position = clock.positionMs()
                PlaybackTrace.log("Librespot", "not recovering $trackId: $why; paused at $position ms")
                wantsPlay = false
                reloadOnResume = true
                runCatching { player?.let(::pauseFromKainos) }
                haltFilter.accept(SpotifyReceiverEvent(SpotifyReceiverEvent.Kind.PAUSED, trackId, position))
                true
            }
        }

    /**
     * Every pause Kainos sends goes through here. librespot acknowledges a pause of a playing track
     * once, on its own event thread, so that acknowledgement can land after a newer Play; counting
     * the pauses sent to this receiver tells it apart from another client's pause.
     */
    private fun pauseFromKainos(receiver: Player) {
        val state = runCatching { receiver.stateWrapper.state }.getOrNull()
        // librespot acknowledges a pause whenever its state was not paused yet.
        if (state != null && !state.isPaused) {
            synchronized(ownPauseLock) {
                if (ownPauseReceiver !== receiver) {
                    ownPauseReceiver = receiver
                    ownPausesPending = 0
                }
                ownPausesPending++
            }
        }
        receiver.pause()
    }

    /** A pause Kainos did not send (another Spotify client, an inactive session) is the listener's. */
    private fun pausedOutsideKainos(receiver: Player, acknowledgement: Boolean) {
        if (acknowledgement) {
            synchronized(ownPauseLock) {
                if (ownPauseReceiver === receiver && ownPausesPending > 0) {
                    ownPausesPending--
                    return
                }
            }
        }
        if (!wantsPlay) return
        transportEpoch.incrementAndGet()
        wantsPlay = false
    }

    /**
     * librespot reports a failed load on its event thread, after the load call returned. During a
     * recovery of [kainosTrackId] that failure belongs to the recovery, which retries and finally
     * pauses; reported as unavailable it would fail the queue entry and skip the song first.
     */
    private fun recoveryOwnsFailure(): Boolean {
        val track = recoveryTrack ?: return false
        if (track != kainosTrackId || track != receiverTrackId) return false
        scheduleRecoveryCheck("a failed load during recovery", RECOVERY_SETTLE_MS)
        return true
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
                val keepalive = LibrespotKeepaliveProbe(createdSession).also { it.attach() }
                val apAddress = AtomicReference(LibrespotApSocket.of(createdSession)?.localAddress)
                createdSession.addReconnectionListener(object : Session.ReconnectionListener {
                    override fun onConnectionDropped() {
                        PlaybackTrace.log(
                            "Librespot",
                            "AP connection dropped on ${network()} (${apAddressState(apAddress.get())}); librespot is reconnecting",
                        )
                        keepalive.dropReport()?.let { PlaybackTrace.log("Librespot", it) }
                        // librespot calls this on every 10-s retry; one check per session is enough.
                        if (idleReplaceScheduled.compareAndSet(false, true)) {
                            recoveryScope.launch { replaceIdleReceiverWhileReconnecting(createdSession) }
                        }
                        // A VPN switch closes the song's audio stream along with the AP connection.
                        if (session === createdSession) scheduleRecoveryCheck("the AP connection dropped", RECOVERY_SETTLE_MS)
                        installLibrespotReconnectGuard(Thread.currentThread()) { error ->
                            Log.w(TAG, "interrupted AP reconnect; recreating receiver", error)
                            recoveryScope.launch {
                                // A shutdown or a replacement wins over recovery of the old session.
                                val playing = mutex.withLock {
                                    if (session !== createdSession) return@launch
                                    player.takeIf { wantsPlay && kainosTrackId != null }
                                }
                                val handled = playing != null &&
                                    recoverIfBroken("its AP reconnect was interrupted", playing, force = true)
                                // Recovery replaced the session or left the track paused; otherwise replace it here.
                                if (!handled) mutex.withLock {
                                    if (session === createdSession) replaceReceiverLocked()
                                }
                            }
                        }
                    }
                    override fun onConnectionEstablished() {
                        PlaybackTrace.log("Librespot", "AP connection re-established on ${network()}")
                        keepalive.attach()
                        apAddress.set(LibrespotApSocket.of(createdSession)?.localAddress)
                    }
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
            stalledSinceMs = null
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
            // A stream that died ends its song where it stopped; a real end is too near the end to reload.
            if (player.isCurrent()) scheduleRecoveryCheck("playback ended early", RECOVERY_SETTLE_MS)
        }

        override fun onPlaybackPaused(player: Player, trackTime: Long) {
            trace("paused at $trackTime")
            // A dying stream does not send this; only a pause command does.
            if (player.isCurrent()) pausedOutsideKainos(player, acknowledgement = true)
            emit(player, SpotifyReceiverEvent.Kind.PAUSED, trackTime)
        }

        override fun onPlaybackResumed(player: Player, trackTime: Long) {
            trace("resumed at $trackTime")
            emit(player, SpotifyReceiverEvent.Kind.PLAYING, trackTime)
        }

        override fun onPlaybackFailed(player: Player, e: Exception) {
            trace("playbackFailed: $e")
            if (player.isCurrent() && recoveryOwnsFailure()) return
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
            if (player.isCurrent()) pausedOutsideKainos(player, acknowledgement = false)
            emit(player, SpotifyReceiverEvent.Kind.PAUSED)
        }

        override fun onVolumeChanged(player: Player, volume: Float) = Unit

        override fun onPanicState(player: Player) {
            trace("PANIC state")
            if (loadingDirectly || (player.isCurrent() && recoveryOwnsFailure())) return
            emit(player, SpotifyReceiverEvent.Kind.UNAVAILABLE)
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
        /** Lets librespot's own events about a broken stream land before judging it. */
        private const val RECOVERY_SETTLE_MS = 2_000L
        /** A VPN coming up can leave the network unvalidated for a few seconds. */
        private const val RECOVERY_NETWORK_WAIT_MS = 30_000L
        private const val RECOVERY_NETWORK_POLL_MS = 1_000L
        private const val RECOVERY_RETRY_MS = 3_000L
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
