package com.universalmusic.player.platform

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.absolutePathString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class JvmSpotifyWebPlaybackHost(
    private val tokenSupplier: SpotifyTokenSupplier,
    private val locator: ChromiumLocator = SystemChromiumLocator,
    private val readyTimeoutMs: Long = 20_000,
) : SpotifyWebPlaybackHost {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val _state = MutableStateFlow<SpotifyWebPlaybackState>(SpotifyWebPlaybackState.Stopped)
    override val state: StateFlow<SpotifyWebPlaybackState> = _state.asStateFlow()

    private var stopServer: (() -> Unit)? = null
    private var port: Int = 0
    private var sessionNonce: String = ""
    private var browserProcess: Process? = null
    private var selectedBrowser: ChromiumInstallation? = null
    private val readySignal = AtomicReference<CompletableDeferred<ReadySignal>?>(null)
    @Volatile private var lastHeartbeatMs: Long = 0L
    @Volatile private var knownDeviceId: String? = null

    private sealed interface ReadySignal {
        data class Device(val deviceId: String) : ReadySignal
        data class Failed(val reason: SpotifyWebPlaybackFailure) : ReadySignal
        data object ActivationRequired : ReadySignal
    }

    override suspend fun ensureDeviceReady(): SpotifyWebPlaybackDevice? = mutex.withLock {
        if (_state.value == SpotifyWebPlaybackState.ActivationRequired) return null
        knownDeviceId?.takeIf { isHeartbeatFresh() }?.let {
            agentLog("H1", "JvmSpotifyWebPlaybackHost.ensureDeviceReady", "reuse ready device", mapOf("devicePrefix" to it.take(8)))
            _state.value = SpotifyWebPlaybackState.Ready(it)
            return SpotifyWebPlaybackDevice(it)
        }
        shutdownLocked()
        _state.value = SpotifyWebPlaybackState.StartingHost
        agentLog("H2", "JvmSpotifyWebPlaybackHost.ensureDeviceReady", "starting host", emptyMap())
        val candidates = locator.findCandidates()
        agentLog(
            "H2",
            "JvmSpotifyWebPlaybackHost.ensureDeviceReady",
            "browser candidates",
            mapOf(
                "count" to candidates.size,
                "labels" to candidates.map { "${it.label}(wv=${it.likelyHasWidevine})" },
            ),
        )
        if (candidates.isEmpty()) {
            _state.value = SpotifyWebPlaybackState.Failed(SpotifyWebPlaybackFailure.BrowserNotFound)
            return null
        }
        try {
            startServerLocked()
        } catch (failure: Exception) {
            agentLog("H3", "JvmSpotifyWebPlaybackHost.ensureDeviceReady", "host startup failed", mapOf("error" to (failure.message ?: "unknown")))
            _state.value = SpotifyWebPlaybackState.Failed(SpotifyWebPlaybackFailure.HostStartupFailed)
            shutdownLocked()
            return null
        }

        var lastFailure: SpotifyWebPlaybackFailure = SpotifyWebPlaybackFailure.BrowserLaunchFailed
        for (candidate in candidates) {
            if (candidate.family == BrowserFamily.CHROMIUM && !candidate.likelyHasWidevine) {
                agentLog(
                    "H2",
                    "JvmSpotifyWebPlaybackHost.ensureDeviceReady",
                    "skip chromium without widevine",
                    mapOf("label" to candidate.label),
                )
                lastFailure = SpotifyWebPlaybackFailure.UnsupportedEnvironment
                continue
            }
            _state.value = SpotifyWebPlaybackState.LaunchingBrowser
            val signal = CompletableDeferred<ReadySignal>()
            readySignal.set(signal)
            val ok = launchBrowserLocked(candidate)
            agentLog(
                "H2",
                "JvmSpotifyWebPlaybackHost.ensureDeviceReady",
                "browser launch attempt",
                mapOf(
                    "label" to candidate.label,
                    "family" to candidate.family.name,
                    "widevine" to candidate.likelyHasWidevine,
                    "ok" to ok,
                ),
            )
            if (!ok) {
                lastFailure = SpotifyWebPlaybackFailure.BrowserLaunchFailed
                stopBrowserOnlyLocked()
                continue
            }
            selectedBrowser = candidate
            _state.value = SpotifyWebPlaybackState.WaitingForSdk
            val result = withTimeoutOrNull(readyTimeoutMs) { signal.await() }
            when (result) {
                is ReadySignal.Device -> {
                    knownDeviceId = result.deviceId
                    lastHeartbeatMs = System.currentTimeMillis()
                    _state.value = SpotifyWebPlaybackState.Ready(result.deviceId)
                    agentLog(
                        "H1",
                        "JvmSpotifyWebPlaybackHost.ensureDeviceReady",
                        "device ready",
                        mapOf("devicePrefix" to result.deviceId.take(8), "browser" to candidate.label),
                    )
                    return SpotifyWebPlaybackDevice(result.deviceId)
                }
                ReadySignal.ActivationRequired -> {
                    _state.value = SpotifyWebPlaybackState.ActivationRequired
                    agentLog("H5", "JvmSpotifyWebPlaybackHost.ensureDeviceReady", "activation required", mapOf("browser" to candidate.label))
                    return null
                }
                is ReadySignal.Failed -> {
                    lastFailure = result.reason
                    agentLog(
                        "H5",
                        "JvmSpotifyWebPlaybackHost.ensureDeviceReady",
                        "browser sdk failed; trying next",
                        mapOf("browser" to candidate.label, "reason" to result.reason.toString()),
                    )
                    // Auth/account errors will not be fixed by another browser.
                    if (result.reason is SpotifyWebPlaybackFailure.AuthenticationFailed ||
                        result.reason is SpotifyWebPlaybackFailure.AccountError ||
                        result.reason is SpotifyWebPlaybackFailure.ReconnectRequired
                    ) {
                        _state.value = SpotifyWebPlaybackState.Failed(result.reason)
                        return null
                    }
                    stopBrowserOnlyLocked()
                    continue
                }
                null -> {
                    lastFailure = SpotifyWebPlaybackFailure.DeviceRegistrationTimedOut
                    agentLog(
                        "H3",
                        "JvmSpotifyWebPlaybackHost.ensureDeviceReady",
                        "device registration timed out; trying next",
                        mapOf("browser" to candidate.label),
                    )
                    stopBrowserOnlyLocked()
                    continue
                }
            }
        }
        _state.value = SpotifyWebPlaybackState.Failed(lastFailure)
        agentLog(
            "H2",
            "JvmSpotifyWebPlaybackHost.ensureDeviceReady",
            "all browsers failed",
            mapOf("lastFailure" to lastFailure.toString()),
        )
        return null
    }

    internal fun receiveReady(deviceId: String) {
        knownDeviceId = deviceId
        lastHeartbeatMs = System.currentTimeMillis()
        _state.value = SpotifyWebPlaybackState.Ready(deviceId)
        readySignal.get()?.complete(ReadySignal.Device(deviceId))
    }

    internal fun receiveActivation() {
        if (_state.value == SpotifyWebPlaybackState.ActivationRequired) {
            knownDeviceId?.let { receiveReady(it) }
        }
    }

    internal fun receiveSdkError(type: String?) {
        if (type != "autoplay_failed") {
            knownDeviceId = null
            lastHeartbeatMs = 0
        }
        val signal = when (type) {
            "autoplay_failed" -> ReadySignal.ActivationRequired
            "initialization_error" -> ReadySignal.Failed(SpotifyWebPlaybackFailure.UnsupportedEnvironment)
            else -> ReadySignal.Failed(mapSdkError(type))
        }
        _state.value = when (signal) {
            ReadySignal.ActivationRequired -> SpotifyWebPlaybackState.ActivationRequired
            is ReadySignal.Failed -> SpotifyWebPlaybackState.Failed(signal.reason)
            is ReadySignal.Device -> SpotifyWebPlaybackState.Ready(signal.deviceId)
        }
        readySignal.get()?.complete(signal)
    }

    override suspend fun shutdown() = mutex.withLock { shutdownLocked() }

    private fun isHeartbeatFresh(): Boolean {
        val last = lastHeartbeatMs
        if (last == 0L) return knownDeviceId != null
        return System.currentTimeMillis() - last < 15_000
    }

    private fun startServerLocked() {
        sessionNonce = newNonce()
        val freePort = java.net.ServerSocket(0).use { it.localPort }
        val engine = embeddedServer(CIO, port = freePort, host = "127.0.0.1") {
            routing {
                get("/") {
                    if (!isLoopback(call.request.local.remoteHost)) {
                        call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
                        return@get
                    }
                    call.response.header("Cache-Control", "no-store")
                    call.respondText(playerHtml(sessionNonce), ContentType.Text.Html)
                }
                get("/health") {
                    call.respondText("ok")
                }
                get("/token") {
                    if (!authorized(call.request.header("X-Kainos-Session"), call.request.local.remoteHost)) {
                        call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
                        return@get
                    }
                    call.response.header("Cache-Control", "no-store")
                    val token = runCatching { tokenSupplier.getValidAccessToken() }.getOrElse { error ->
                        agentLog("H4", "JvmSpotifyWebPlaybackHost./token", "token supplier failed", mapOf("error" to (error.message ?: "unknown")))
                        _state.value = SpotifyWebPlaybackState.Failed(SpotifyWebPlaybackFailure.ReconnectRequired)
                        call.respondText("""{"error":"auth"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                        return@get
                    }
                    call.respondText("""{"access_token":${json.encodeToString(token)}}""", ContentType.Application.Json)
                }
                post("/ready") {
                    if (!authorized(call.request.header("X-Kainos-Session"), call.request.local.remoteHost)) {
                        call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
                        return@post
                    }
                    val body = runCatching { json.decodeFromString(ReadyBody.serializer(), call.receiveText()) }.getOrNull()
                    val deviceId = body?.deviceId?.trim().orEmpty()
                    if (deviceId.isBlank()) {
                        call.respondText("bad request", status = HttpStatusCode.BadRequest)
                        return@post
                    }
                    receiveReady(deviceId)
                    agentLog("H3", "JvmSpotifyWebPlaybackHost./ready", "device_id received", mapOf("devicePrefix" to deviceId.take(8)))
                    call.respondText("ok")
                }
                post("/error") {
                    if (!authorized(call.request.header("X-Kainos-Session"), call.request.local.remoteHost)) {
                        call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
                        return@post
                    }
                    val body = runCatching { json.decodeFromString(ErrorBody.serializer(), call.receiveText()) }.getOrNull()
                    agentLog("H5", "JvmSpotifyWebPlaybackHost./error", "sdk error", mapOf("type" to (body?.type ?: "unknown"), "message" to (body?.message ?: "")))
                    receiveSdkError(body?.type)
                    call.respondText("ok")
                }
                post("/activated") {
                    if (!authorized(call.request.header("X-Kainos-Session"), call.request.local.remoteHost)) {
                        call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
                        return@post
                    }
                    receiveActivation()
                    call.respondText("ok")
                }
                post("/heartbeat") {
                    if (!authorized(call.request.header("X-Kainos-Session"), call.request.local.remoteHost)) {
                        call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
                        return@post
                    }
                    lastHeartbeatMs = System.currentTimeMillis()
                    call.respondText("ok")
                }
            }
        }
        engine.start(wait = false)
        port = freePort
        stopServer = { engine.stop(200, 400) }
        agentLog("H3", "JvmSpotifyWebPlaybackHost.startServer", "HTTP host ready", mapOf("port" to port))
    }

    private fun launchBrowserLocked(candidate: ChromiumInstallation): Boolean {
        val profile = kainosWebPlayerProfileDir()
        Files.createDirectories(profile)
        val url = "http://127.0.0.1:$port/"
        val command = when (candidate.family) {
            BrowserFamily.CHROMIUM -> listOf(
                candidate.executable.absolutePathString(),
                "--user-data-dir=${profile.absolutePathString()}",
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-features=TranslateUI",
                "--app=$url",
            )
            BrowserFamily.FIREFOX -> listOf(
                candidate.executable.absolutePathString(),
                "--new-instance",
                "-profile",
                profile.resolve("firefox-profile").also { Files.createDirectories(it) }.absolutePathString(),
                url,
            )
        }
        return runCatching {
            val process = ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            browserProcess = process
            // Give wrappers a moment; child may keep running even if parent exits later.
            delayBlocking(300)
            true
        }.getOrDefault(false)
    }

    private fun stopBrowserOnlyLocked() {
        readySignal.getAndSet(null)?.cancel()
        runCatching { browserProcess?.destroyForcibly() }
        browserProcess = null
        selectedBrowser = null
    }

    private fun shutdownLocked() {
        readySignal.getAndSet(null)?.cancel()
        knownDeviceId = null
        lastHeartbeatMs = 0
        runCatching { stopServer?.invoke() }
        stopServer = null
        port = 0
        sessionNonce = ""
        runCatching { browserProcess?.destroyForcibly() }
        browserProcess = null
        selectedBrowser = null
        _state.value = SpotifyWebPlaybackState.Stopped
    }

    private fun authorized(header: String?, remoteHost: String): Boolean =
        isLoopback(remoteHost) && !sessionNonce.isBlank() && header == sessionNonce

    private fun isLoopback(host: String): Boolean =
        host == "127.0.0.1" || host == "::1" || host == "localhost" || host.isBlank()

    private fun mapSdkError(type: String?): SpotifyWebPlaybackFailure = when (type) {
        "initialization_error" -> SpotifyWebPlaybackFailure.UnsupportedEnvironment
        "authentication_error" -> SpotifyWebPlaybackFailure.AuthenticationFailed
        "account_error" -> SpotifyWebPlaybackFailure.AccountError
        "playback_error" -> SpotifyWebPlaybackFailure.PlaybackError
        "autoplay_failed" -> SpotifyWebPlaybackFailure.PlaybackError
        "sdk_load_failed" -> SpotifyWebPlaybackFailure.SdkLoadFailed
        else -> SpotifyWebPlaybackFailure.Message(type ?: "unknown")
    }

    private fun newNonce(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun delayBlocking(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    @Serializable
    private data class ReadyBody(val deviceId: String? = null)

    @Serializable
    private data class ErrorBody(val type: String? = null, val message: String? = null)

    private fun playerHtml(nonce: String): String = """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8"/>
  <title>Kainos Player</title>
  <style>
    body { font-family: system-ui, sans-serif; background:#111; color:#eee; display:flex; align-items:center; justify-content:center; min-height:100vh; margin:0; }
    .card { max-width: 28rem; text-align:center; padding:1.5rem; }
    button { font-size:1rem; padding:0.75rem 1.25rem; border-radius:0.5rem; border:0; background:#1DB954; color:#000; cursor:pointer; }
    .hidden { display:none; }
    .muted { color:#aaa; font-size:0.9rem; }
  </style>
</head>
<body>
  <div class="card">
    <h1>Kainos Player</h1>
    <p id="status" class="muted">Connecting to Spotify…</p>
    <div id="activate" class="hidden">
      <p>Spotify needs one-time audio activation.</p>
      <button id="enable">Enable Audio</button>
    </div>
  </div>
  <script>
    const SESSION = ${Json.encodeToString(nonce)};
    const headers = { 'Content-Type': 'application/json', 'X-Kainos-Session': SESSION };
    const statusEl = document.getElementById('status');
    const activateEl = document.getElementById('activate');
    let player = null;

    function setStatus(text) { statusEl.textContent = text; }

    async function post(path, body) {
      await fetch(path, { method: 'POST', headers, body: JSON.stringify(body || {}) });
    }

    async function reportError(type, message) {
      setStatus(type + (message ? (': ' + message) : ''));
      try { await post('/error', { type, message: message || '' }); } catch (e) {}
    }

    async function fetchToken(cb) {
      try {
        const res = await fetch('/token', { headers: { 'X-Kainos-Session': SESSION } });
        if (!res.ok) {
          await reportError('authentication_error', 'token endpoint ' + res.status);
          return;
        }
        const data = await res.json();
        if (!data.access_token) {
          await reportError('authentication_error', 'missing access_token');
          return;
        }
        cb(data.access_token);
      } catch (e) {
        await reportError('authentication_error', String(e));
      }
    }

    function startHeartbeat() {
      setInterval(() => { post('/heartbeat', {}); }, 4000);
    }

    window.onSpotifyWebPlaybackSDKReady = () => {
      setStatus('SDK ready — connecting…');
      const probe = navigator.requestMediaKeySystemAccess
        ? navigator.requestMediaKeySystemAccess('com.widevine.alpha', [{
            initDataTypes: ['cenc'],
            audioCapabilities: [{ contentType: 'audio/mp4; codecs="mp4a.40.2"' }]
          }]).then(() => true).catch(() => false)
        : Promise.resolve(false);
      probe.then(async (emeOk) => {
        if (!emeOk) {
          await reportError('initialization_error', 'Widevine/EME unavailable in this browser. Install Google Chrome.');
          return;
        }
        player = new Spotify.Player({
          name: 'Kainos Player',
          getOAuthToken: cb => { fetchToken(cb); },
          volume: 0.8
        });
        player.addListener('ready', ({ device_id }) => {
          setStatus('Ready');
          post('/ready', { deviceId: device_id });
          startHeartbeat();
        });
        player.addListener('not_ready', ({ device_id }) => {
          reportError('not_ready', device_id || '');
        });
        player.addListener('initialization_error', ({ message }) => reportError('initialization_error', message));
        player.addListener('authentication_error', ({ message }) => reportError('authentication_error', message));
        player.addListener('account_error', ({ message }) => reportError('account_error', message));
        player.addListener('playback_error', ({ message }) => reportError('playback_error', message));
        player.addListener('autoplay_failed', () => {
          activateEl.classList.remove('hidden');
          setStatus('Activation required');
          reportError('autoplay_failed', '');
        });
        document.getElementById('enable').onclick = async () => {
          try {
            await player.activateElement();
            await post('/activated', {});
            activateEl.classList.add('hidden');
            setStatus('Audio enabled');
          } catch (e) {
            reportError('playback_error', String(e));
          }
        };
        player.connect();
      });
    };

    const script = document.createElement('script');
    script.src = 'https://sdk.scdn.co/spotify-player.js';
    script.async = true;
    script.onerror = () => reportError('sdk_load_failed', 'script load failed');
    document.body.appendChild(script);
  </script>
</body>
</html>
""".trimIndent()
}

// #region agent log
private fun agentLog(hypothesisId: String, location: String, message: String, data: Map<String, Any?>) {
    runCatching {
        val payload = buildString {
            append('{')
            append("\"sessionId\":\"0575ce\",")
            append("\"hypothesisId\":").append(jsonQuote(hypothesisId)).append(',')
            append("\"location\":").append(jsonQuote(location)).append(',')
            append("\"message\":").append(jsonQuote(message)).append(',')
            append("\"timestamp\":").append(System.currentTimeMillis()).append(',')
            append("\"data\":{")
            append(data.entries.joinToString(",") { (k, v) ->
                jsonQuote(k) + ":" + when (v) {
                    null -> "null"
                    is Number, is Boolean -> v.toString()
                    is List<*> -> v.joinToString(",", "[", "]") { jsonQuote(it.toString()) }
                    else -> jsonQuote(v.toString())
                }
            })
            append("}}")
            append('\n')
        }
        Files.writeString(
            Path.of("/home/theadenkingof/Documents/Code/Kainos-Player/.cursor/debug-0575ce.log"),
            payload,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND,
        )
    }
}

private fun jsonQuote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
// #endregion
