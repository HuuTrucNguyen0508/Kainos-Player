package com.universalmusic.player.data.sync

import com.universalmusic.player.data.cache.CachedAudioCopies
import com.universalmusic.player.data.cache.CachedAudioCopyRefused
import com.universalmusic.player.data.cache.CachedAudioWrite
import com.universalmusic.player.data.library.LibraryRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val CACHED_AUDIO_ALREADY_CACHED = "already cached"

/**
 * This device's side of a cached-audio exchange: hearted entries it may offer to a peer on
 * [LibraryRepository.currentSpotifyAccountId] (Spotify matches only under a known account).
 */
suspend fun cachedAudioDocument(deviceId: String, library: LibraryRepository, copies: CachedAudioCopies): CachedAudioSyncDocument {
    val favorites = library.favoriteIds.value
    val account = library.currentSpotifyAccountId
    val tracks = library.savedTracks.value.associateBy { it.canonicalId }
    val offers = copies.offers { id -> tracks[id]?.let { it.title to it.artistLine } }
        .filter { it.ownerCanonicalId in favorites && cachedAudioMayCross(it.ownerCanonicalId, account, account) }
    return CachedAudioSyncDocument(deviceId, account, offers, copies.heldOwners().sorted())
}

/**
 * Pending copies after an exchange: the peer's offers this device hearts and lacks, and this device's
 * offers the peer lacks, each only when a Spotify match may cross between the two accounts.
 */
fun planCachedAudioTransfers(
    local: CachedAudioSyncDocument,
    remote: CachedAudioSyncDocument,
    localFavorites: Set<String>,
): List<PendingCachedAudioTransfer> {
    fun mayCross(offer: CachedAudioOffer) = isCachedAudioFileToken(offer.fileToken) && offer.sizeBytes > 0L &&
        cachedAudioMayCross(offer.ownerCanonicalId, local.spotifyAccountId, remote.spotifyAccountId)
    val localHeld = local.heldOwners.toSet() + local.offers.map { it.ownerCanonicalId }
    val remoteHeld = remote.heldOwners.toSet() + remote.offers.map { it.ownerCanonicalId }
    val down = remote.offers.filter { mayCross(it) && it.ownerCanonicalId in localFavorites && it.ownerCanonicalId !in localHeld }
    val up = local.offers.filter { mayCross(it) && it.ownerCanonicalId !in remoteHeld }
    return down.distinctBy { it.ownerCanonicalId }.map { PendingCachedAudioTransfer(it, VaultCopyDirection.TO_LOCAL) } +
        up.distinctBy { it.ownerCanonicalId }.map { PendingCachedAudioTransfer(it, VaultCopyDirection.TO_REMOTE) }
}

/**
 * Copies one confirmed cached file. A download stages its bytes and publishes only when the whole file
 * matches the hub's SHA-256; an upload sends this device's hash so the hub checks the same. Any failure
 * discards the staged bytes, so Transfer again starts clean. Returns a short outcome for the status line.
 */
suspend fun executeCachedAudioTransfer(
    copies: CachedAudioCopies,
    client: HomeLanSyncClient,
    transfer: PendingCachedAudioTransfer,
    stillWanted: () -> Boolean,
    onProgress: suspend (detail: String, transferred: Long, total: Long) -> Unit,
): String {
    val offer = transfer.offer
    val owner = offer.ownerCanonicalId
    val size = offer.sizeBytes
    val name = offer.title.ifBlank { owner }
    when (transfer.direction) {
        VaultCopyDirection.TO_LOCAL -> {
            if (copies.holds(owner)) return "$name was already cached"
            copies.beginIncoming(offer)
            try {
                val hash = client.fetchCachedAudioHash(owner, offer.fileToken).getOrThrow()
                var offset = 0L
                var outcome = CachedAudioWrite.STAGED
                while (offset < size) {
                    val length = minOf(VAULT_BLOB_CHUNK_BYTES.toLong(), size - offset).toInt()
                    val chunk = client.downloadCachedAudio(owner, offer.fileToken, offset, length).getOrThrow()
                    check(chunk.size == length) { "Short chunk for $name at $offset (${chunk.size} of $length bytes)" }
                    outcome = copies.writeIncoming(offer, offset, chunk, hash, stillWanted)
                    offset += length
                    onProgress("Downloading $name", offset, size)
                }
                return when (outcome) {
                    CachedAudioWrite.PUBLISHED -> "Copied $name"
                    CachedAudioWrite.ALREADY_CACHED -> "$name was already cached"
                    CachedAudioWrite.NOT_WANTED -> "Skipped $name (no longer hearted)"
                    CachedAudioWrite.STAGED -> error("Copy of $name did not complete")
                }
            } catch (err: Throwable) {
                copies.discardIncoming(offer)
                throw err
            }
        }
        VaultCopyDirection.TO_REMOTE -> {
            val hash = copies.sha256(owner, offer.fileToken) ?: error("$name is no longer cached here; sync again")
            check(copies.size(owner, offer.fileToken) == size) { "$name changed here; sync again" }
            var offset = 0L
            while (offset < size) {
                val length = minOf(VAULT_BLOB_CHUNK_BYTES.toLong(), size - offset).toInt()
                val chunk = copies.read(owner, offer.fileToken, offset, length)
                    ?: error("$name is no longer cached here; sync again")
                val reply = client.uploadCachedAudio(owner, offer.fileToken, offset, size, hash, chunk).getOrThrow()
                if (reply == CACHED_AUDIO_ALREADY_CACHED) return "$name was already cached on the hub"
                offset += length
                onProgress("Uploading $name", offset, size)
            }
            return "Copied $name"
        }
    }
}

/**
 * The hub's side of the cached-audio routes. It serves and accepts only hearted owners, Spotify matches
 * only to the peer account it last heard in an exchange, and uploads only for files that peer offered.
 */
class CachedAudioHost(
    private val hubDeviceId: () -> String,
    private val library: LibraryRepository,
    private val copies: CachedAudioCopies,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private class PeerView(val spotifyAccountId: String?, val offers: Map<String, CachedAudioOffer>)

    private val lock = Mutex()
    private val peers = HashMap<String, PeerView>()

    private suspend fun view(peer: PairedPeer) = lock.withLock { peers[peer.deviceId] }

    private suspend fun serves(peer: PairedPeer, owner: String): Boolean =
        library.isFavorite(owner) &&
            cachedAudioMayCross(owner, library.currentSpotifyAccountId, view(peer)?.spotifyAccountId)

    suspend fun exchange(peer: PairedPeer, body: String): HubAnswer {
        val remote = runCatching { json.decodeFromString<CachedAudioSyncDocument>(body) }.getOrNull()
            ?: return HubAnswer(400, "Malformed cached audio index")
        library.awaitLoaded()
        lock.withLock {
            peers[peer.deviceId] = PeerView(remote.spotifyAccountId, remote.offers.associateBy { it.ownerCanonicalId })
        }
        val local = cachedAudioDocument(hubDeviceId(), library, copies)
        val reply = local.copy(offers = local.offers.filter {
            cachedAudioMayCross(it.ownerCanonicalId, local.spotifyAccountId, remote.spotifyAccountId)
        })
        return HubAnswer(200, json.encodeToString(reply))
    }

    /** Size of the served file, or null (404) when this owner's file is not one the peer may read. */
    suspend fun size(peer: PairedPeer, owner: String, token: String): Long? =
        if (serves(peer, owner)) copies.size(owner, token) else null

    suspend fun read(peer: PairedPeer, owner: String, token: String, offset: Long, length: Int): ByteArray? =
        if (serves(peer, owner)) copies.read(owner, token, offset, length) else null

    suspend fun hash(peer: PairedPeer, owner: String, token: String): String? =
        if (serves(peer, owner)) copies.sha256(owner, token) else null

    suspend fun receive(
        peer: PairedPeer,
        owner: String,
        token: String,
        offset: Long,
        total: Long,
        sha256: String,
        chunk: ByteArray,
    ): HubAnswer {
        val offer = view(peer)?.offers?.get(owner)?.takeIf { it.fileToken == token && it.sizeBytes == total }
            ?: return HubAnswer(409, "This file was not offered in the last sync; sync again")
        if (!serves(peer, owner)) return HubAnswer(403, "Not hearted on this hub")
        return try {
            if (offset == 0L) {
                if (copies.holds(owner)) return HubAnswer(200, CACHED_AUDIO_ALREADY_CACHED)
                copies.beginIncoming(offer)
            }
            when (copies.writeIncoming(offer, offset, chunk, sha256) { library.isFavorite(owner) }) {
                CachedAudioWrite.STAGED -> HubAnswer(200, "ok")
                CachedAudioWrite.PUBLISHED -> HubAnswer(200, "published")
                CachedAudioWrite.ALREADY_CACHED -> HubAnswer(200, CACHED_AUDIO_ALREADY_CACHED)
                CachedAudioWrite.NOT_WANTED -> HubAnswer(403, "Not hearted on this hub")
            }
        } catch (refused: CachedAudioCopyRefused) {
            HubAnswer(refused.status, refused.message.orEmpty())
        }
    }
}
