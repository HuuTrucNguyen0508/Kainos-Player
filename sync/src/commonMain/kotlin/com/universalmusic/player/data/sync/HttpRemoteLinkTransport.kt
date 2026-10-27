package com.universalmusic.player.data.sync

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

class HttpRemoteLinkTransport(
    private val endpoint: HubEndpoint,
    private val http: HttpClient,
    private val json: Json = remoteJson,
) : RemoteLinkTransport {
    private val base get() = "https://${endpoint.authority}"

    override fun close() = http.close()

    override suspend fun poll(request: RemotePollRequest): RemotePollReply {
        val response = request {
            http.post("$base$HOME_LAN_SYNC_PATH_V3_REMOTE_POLL") {
                expectSuccess = false
                timeout {
                    requestTimeoutMillis = REMOTE_POLL_REQUEST_TIMEOUT_MS
                    socketTimeoutMillis = REMOTE_POLL_REQUEST_TIMEOUT_MS
                }
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(RemotePollRequest.serializer(), request))
            }
        }
        return json.decodeFromString(RemotePollReply.serializer(), response.checked("Remote poll").bodyAsText())
    }

    override suspend fun command(command: RemoteCommand): RemoteCommandReply {
        val response = request {
            http.post("$base$HOME_LAN_SYNC_PATH_V3_REMOTE_COMMAND") {
                expectSuccess = false
                commandTimeout()
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(RemoteCommand.serializer(), command))
            }
        }
        if (response.status == HttpStatusCode.Conflict) throw RemoteHubHasNoPlayer()
        return json.decodeFromString(RemoteCommandReply.serializer(), response.checked("Remote command").bodyAsText())
    }

    override suspend fun postTransfer(epoch: String, seq: Long, reply: RemoteTransferReply) {
        request {
            http.post("$base$HOME_LAN_SYNC_PATH_V3_REMOTE_TRANSFER/$epoch/$seq") {
                expectSuccess = false
                commandTimeout()
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(RemoteTransferReply.serializer(), reply))
            }
        }.checked("Remote transfer")
    }

    override suspend fun putArtwork(key: String, bytes: ByteArray) {
        request {
            http.put("$base$HOME_LAN_SYNC_PATH_V3_REMOTE_ARTWORK/$key") {
                expectSuccess = false
                artworkTimeout()
                contentType(ContentType.Application.OctetStream)
                setBody(bytes)
            }
        }.checked("Remote artwork upload")
    }

    override suspend fun fetchArtwork(key: String): ByteArray? {
        val response = request {
            http.get("$base$HOME_LAN_SYNC_PATH_V3_REMOTE_ARTWORK/$key") {
                expectSuccess = false
                artworkTimeout()
            }
        }
        if (response.status == HttpStatusCode.NotFound) return null
        return response.checked("Remote artwork").bodyAsBytes().takeIf { it.size <= REMOTE_ARTWORK_MAX_BYTES }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.commandTimeout() = timeout {
        requestTimeoutMillis = REMOTE_COMMAND_REQUEST_TIMEOUT_MS
        socketTimeoutMillis = REMOTE_COMMAND_REQUEST_TIMEOUT_MS
    }

    private fun io.ktor.client.request.HttpRequestBuilder.artworkTimeout() = timeout {
        requestTimeoutMillis = REMOTE_ARTWORK_REQUEST_TIMEOUT_MS
        socketTimeoutMillis = REMOTE_ARTWORK_REQUEST_TIMEOUT_MS
    }

    private suspend fun request(block: suspend () -> HttpResponse): HttpResponse = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (err: Exception) {
        throw trustFailureOf(err)?.let(::ListeningHubRefused) ?: err
    }

    private suspend fun HttpResponse.checked(what: String): HttpResponse {
        if (status.isSuccess()) return this
        val detail = runCatching { bodyAsText() }.getOrDefault("").take(200)
        if (status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden) {
            throw ListeningHubRefused("$what refused: HTTP ${status.value} $detail")
        }
        error("$what failed: HTTP ${status.value} $detail")
    }
}

internal val remoteJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
