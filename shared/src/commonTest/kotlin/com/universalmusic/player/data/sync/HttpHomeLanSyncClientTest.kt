package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class HttpHomeLanSyncClientTest {
    private val pairing = HomeLanSyncPairing(
        deviceId = "phone",
        sharedSecretHex = "aabbccddeeff00112233445566778899",
        hubHost = "192.168.1.10",
        hubCertSha256Hex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        encryptPayloads = false,
    )

    private fun client(
        pairing: HomeLanSyncPairing = this.pairing,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpHomeLanSyncClient =
        HttpHomeLanSyncClient(
            pairing,
            LibraryRepository(),
            KainosPlaylistRepository(),
            HttpClient(MockEngine(handler)),
        )

    @Test
    fun uploadFailsOnNon2xxPut() = runTest {
        val result = client { respond("disk full", HttpStatusCode.InternalServerError) }
            .use { it.uploadBlob("a.flac", 0L, 3L, byteArrayOf(1, 2, 3)) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("500"))
    }

    @Test
    fun uploadSucceedsOn2xxPut() = runTest {
        val result = client { respond("ok", HttpStatusCode.OK) }
            .use { it.uploadBlob("a.flac", 0L, 3L, byteArrayOf(1, 2, 3)) }
        assertTrue(result.isSuccess)
    }

    @Test
    fun downloadRejectsErrorBodyAndFullResponses() = runTest {
        val unauthorized = client { respond("Unauthorized", HttpStatusCode.Unauthorized) }
            .use { it.downloadBlob("a.flac", 0L, 5) }
        assertTrue(unauthorized.isFailure)
        // A 200 error page must never be accepted as audio bytes.
        val fullBody = client { respond("oops!", HttpStatusCode.OK) }
            .use { it.downloadBlob("a.flac", 0L, 5) }
        assertTrue(fullBody.isFailure)
    }

    @Test
    fun downloadValidatesContentRangeAndLength() = runTest {
        val wrongStart = client {
            respond(
                byteArrayOf(1, 2, 3, 4, 5),
                HttpStatusCode.PartialContent,
                headersOf(HttpHeaders.ContentRange, "bytes 10-14/100"),
            )
        }.use { it.downloadBlob("a.flac", 0L, 5) }
        assertTrue(wrongStart.isFailure)

        val shortBody = client {
            respond(
                byteArrayOf(1, 2, 3),
                HttpStatusCode.PartialContent,
                headersOf(HttpHeaders.ContentRange, "bytes 0-4/100"),
            )
        }.use { it.downloadBlob("a.flac", 0L, 5) }
        assertTrue(shortBody.isFailure)

        val missingRange = client {
            respond(byteArrayOf(1, 2, 3, 4, 5), HttpStatusCode.PartialContent)
        }.use { it.downloadBlob("a.flac", 0L, 5) }
        assertTrue(missingRange.isFailure)
    }

    @Test
    fun downloadAcceptsMatchingPartialContent() = runTest {
        val ok = client { request ->
            assertTrue(request.headers[HttpHeaders.Range] == "bytes=0-4")
            respond(
                byteArrayOf(1, 2, 3, 4, 5),
                HttpStatusCode.PartialContent,
                headersOf(HttpHeaders.ContentRange, "bytes 0-4/100"),
            )
        }.use { it.downloadBlob("a.flac", 0L, 5) }
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), ok.getOrThrow())
    }

    @Test
    fun downloadDecryptsAndChecksPlaintextLength() = runTest {
        val encrypted = pairing.copy(encryptPayloads = true)
        val key = SyncPayloadCrypto.keyFromSharedSecret(encrypted.sharedSecretHex)
        val sealed = SyncPayloadCrypto.encrypt(byteArrayOf(9, 8, 7), key)
        val ok = client(encrypted) {
            respond(sealed, HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, "bytes 0-2/3"))
        }.use { it.downloadBlob("a.flac", 0L, 3) }
        assertContentEquals(byteArrayOf(9, 8, 7), ok.getOrThrow())
    }
}
