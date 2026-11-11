package com.universalmusic.player.platform

import com.universalmusic.player.data.sync.CertFingerprint
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Response

private val androidDeviceIdentity by lazy { AndroidDeviceIdentity() }

actual fun platformDeviceIdentity(): DeviceIdentity = androidDeviceIdentity

/** The SSL context for one pairing: the hub pin it trusts and this device's identity at the time. */
private data class PinnedContextKey(val pin: CertFingerprint, val identity: CertFingerprint)

private class PinnedContext(val ssl: SSLContext, val trustManager: X509TrustManager)

/**
 * Listening delivery, the remote link and every probe each open a client, and building an SSL
 * context per client cost a key-store read and several trace lines each time. A pairing reuses
 * one; an identity reset or a new pin makes a new key.
 */
private val pinnedContexts = object : LinkedHashMap<PinnedContextKey, PinnedContext>(4, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PinnedContextKey, PinnedContext>?) = size > 4
}

private fun pinnedContext(pin: CertFingerprint): PinnedContext {
    val key = PinnedContextKey(pin, androidDeviceIdentity.fingerprint())
    return synchronized(pinnedContexts) { pinnedContexts.getOrPut(key) { newPinnedContext(pin) } }
}

private fun newPinnedContext(pin: CertFingerprint): PinnedContext {
    val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            throw CertificateException("Client certs not supported")

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull()
                ?: throw CertificateException("Empty certificate chain")
            if (CertFingerprint.ofCertificate(leaf.encoded) != pin) {
                throw CertificateException(HUB_PIN_MISMATCH)
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val started = System.nanoTime()
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(arrayOf(androidDeviceIdentity.keyManager()), arrayOf(trustManager), SecureRandom())
    }
    PlaybackTrace.log("HomeSync", "TLS client context for pin ${pin.hex.take(8)} ready in ${(System.nanoTime() - started) / 1_000_000} ms provider=${sslContext.provider.name}")
    return PinnedContext(sslContext, trustManager)
}

actual fun createPinnedHomeLanHttpClient(pin: CertFingerprint): HttpClient {
    val context = pinnedContext(pin)
    return HttpClient(OkHttp) {
        engine {
            config {
                sslSocketFactory(context.ssl.socketFactory, context.trustManager)
                hostnameVerifier { _, _ -> true }
                eventListenerFactory { call -> HomeLanTlsEvents(call) }
            }
        }
        // Non-2xx hub replies (401/403/409/416/5xx) fail the call instead of being read as data.
        expectSuccess = true
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 120_000
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
        }
    }
}

private class HomeLanTlsEvents(call: Call) : EventListener() {
    private val traceRequests = call.request().method == "POST" &&
        call.request().url.encodedPath.startsWith("/kainos-sync/v3/pair/")
    private val started = System.nanoTime()

    private fun trace(message: String) {
        PlaybackTrace.log("HomeSync", "TLS +${(System.nanoTime() - started) / 1_000_000} ms $message")
    }

    /** Per-connection steps; each sync run and delivery already logs its own outcome. Pairing keeps them. */
    private fun detail(message: String) {
        if (traceRequests) trace(message)
        else PlaybackTrace.debug("HomeSync", "TLS +${(System.nanoTime() - started) / 1_000_000} ms $message")
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        detail("connect start $inetSocketAddress")
    }

    override fun secureConnectStart(call: Call) {
        detail("handshake start")
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        detail("handshake complete protocol=${handshake?.tlsVersion} cipher=${handshake?.cipherSuite}")
    }

    override fun requestHeadersStart(call: Call) {
        if (traceRequests) trace("request ${call.request().method} ${call.request().url.encodedPath}")
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        if (traceRequests || !response.isSuccessful) trace("response ${response.code}")
    }

    override fun callFailed(call: Call, ioe: IOException) {
        detail("failed ${ioe.javaClass.name}: ${ioe.message}")
    }
}
