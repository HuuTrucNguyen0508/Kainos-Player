package com.universalmusic.player.platform

import com.universalmusic.player.data.sync.HomeLanSyncPairing
import com.universalmusic.player.data.sync.certSha256Hex
import com.universalmusic.player.data.sync.generateHomeLanHubTls
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.json.Json

actual fun createPinnedHomeLanHttpClient(certPinHex: String): HttpClient {
    val expected = certPinHex.trim().lowercase()
    require(expected.matches(Regex("[0-9a-f]{64}"))) { "Invalid hub certificate pin" }
    val pinTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            throw CertificateException("Client certs not supported")

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull()
                ?: throw CertificateException("Empty certificate chain")
            val pin = certSha256Hex(leaf.encoded)
            if (!pin.equals(expected, ignoreCase = true)) {
                throw CertificateException("Hub certificate pin mismatch")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    return HttpClient(CIO) {
        engine {
            https {
                trustManager = pinTrustManager
            }
        }
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

actual fun generateHomeLanHubCertPin(pairing: HomeLanSyncPairing): String? {
    val material = generateHomeLanHubTls(
        configDir = homeLanConfigDir().toFile(),
        sharedSecretHex = pairing.sharedSecretHex,
        extraHosts = listOfNotNull(pairing.hubHost),
    )
    return material.certSha256Hex
}
