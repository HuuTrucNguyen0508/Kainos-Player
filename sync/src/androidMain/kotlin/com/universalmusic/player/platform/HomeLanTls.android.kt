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

actual fun createPinnedHomeLanHttpClient(pin: CertFingerprint): HttpClient {
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
    PlaybackTrace.log("HomeSync", "TLS client context start")
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(arrayOf(androidDeviceIdentity.keyManager()), arrayOf(trustManager), SecureRandom())
    }
    PlaybackTrace.log("HomeSync", "TLS client context ready in ${(System.nanoTime() - started) / 1_000_000} ms provider=${sslContext.provider.name}")
    return HttpClient(OkHttp) {
        engine {
            config {
                sslSocketFactory(sslContext.socketFactory, trustManager)
                hostnameVerifier { _, _ -> true }
                eventListenerFactory { HomeLanTlsEvents() }
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

private class HomeLanTlsEvents : EventListener() {
    private val started = System.nanoTime()

    private fun trace(message: String) {
        PlaybackTrace.log("HomeSync", "TLS +${(System.nanoTime() - started) / 1_000_000} ms $message")
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        trace("connect start $inetSocketAddress")
    }

    override fun secureConnectStart(call: Call) {
        trace("handshake start")
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        trace("handshake complete protocol=${handshake?.tlsVersion} cipher=${handshake?.cipherSuite}")
    }

    override fun requestHeadersStart(call: Call) {
        trace("request ${call.request().method} ${call.request().url.encodedPath}")
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        trace("response ${response.code}")
    }

    override fun callFailed(call: Call, ioe: IOException) {
        trace("failed ${ioe.javaClass.name}: ${ioe.message}")
    }
}
