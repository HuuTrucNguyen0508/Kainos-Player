package com.universalmusic.player.data.cache

import com.universalmusic.player.data.sync.CachedAudioOffer
import com.universalmusic.player.data.sync.isCachedAudioFileToken
import com.universalmusic.player.platform.currentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

/** Why a copy step was refused; [status] is the HTTP status a hub answers with. */
class CachedAudioCopyRefused(val status: Int, message: String) : IllegalStateException(message)

enum class CachedAudioWrite { STAGED, PUBLISHED, ALREADY_CACHED, NOT_WANTED }

/**
 * Hearted-cache files as Home sync sees them. Reads reach only the file the cache index names for an
 * owner, matched by its file token, so no request can name another path. Incoming copies stage under
 * `incoming/` (outside the budgeted audio directory) and enter the cache only after their size and
 * SHA-256 match, through [HeartedAudioCacheService.adoptCopiedFile]. A failed copy leaves no file behind.
 */
class CachedAudioCopies(
    private val cache: HeartedAudioCache,
    private val service: HeartedAudioCacheService,
    private val newFileBase: () -> String = { UUID.randomUUID().toString().replace("-", "") },
    private val clock: () -> Long = ::currentTimeMillis,
) {
    private val incomingMutex = Mutex()
    @Volatile private var swept = false

    private fun audioDir() = File(cache.audioDirectoryPath())
    private fun incomingDir() = File(audioDir().parentFile, "incoming").apply { mkdirs() }

    /** Offerable entries: a YouTube file named by this app and still on disk. */
    suspend fun offers(describe: (ownerCanonicalId: String) -> Pair<String, String>?): List<CachedAudioOffer> =
        withContext(Dispatchers.Default) {
            cache.allEntries().mapNotNull { entry ->
                val file = fileOf(entry.localUri)?.takeIf { it.isFile && it.length() > 0L } ?: return@mapNotNull null
                if (entry.youtubeVideoId.isBlank() || !isCachedAudioFileToken(file.name)) return@mapNotNull null
                val (title, artistLine) = describe(entry.ownerCanonicalId) ?: ("" to "")
                CachedAudioOffer(
                    ownerCanonicalId = entry.ownerCanonicalId,
                    youtubeVideoId = entry.youtubeVideoId,
                    fileToken = file.name,
                    sizeBytes = file.length(),
                    title = title,
                    artistLine = artistLine,
                    qualityTier = entry.qualityTier,
                    qualityCodec = entry.qualityCodec,
                    qualityBitrateKbps = entry.qualityBitrateKbps,
                    qualitySampleRateHz = entry.qualitySampleRateHz,
                    spotifyViaYouTubeMatch = entry.spotifyViaYouTubeMatch,
                )
            }
        }

    suspend fun heldOwners(): Set<String> = cache.allEntries().mapTo(HashSet()) { it.ownerCanonicalId }

    suspend fun holds(ownerCanonicalId: String): Boolean = cache.get(ownerCanonicalId) != null

    /** The cached file [token] for [owner], or null once the cache no longer points at it. */
    private suspend fun currentFile(owner: String, token: String): File? {
        if (!isCachedAudioFileToken(token)) return null
        val entry = cache.get(owner) ?: return null
        return fileOf(entry.localUri)?.takeIf { it.name == token && it.isFile && it.length() > 0L }
    }

    suspend fun size(owner: String, token: String): Long? = currentFile(owner, token)?.length()

    suspend fun sha256(owner: String, token: String): String? = withContext(Dispatchers.Default) {
        currentFile(owner, token)?.let(::sha256Hex)
    }

    /** Exactly [length] bytes at [offset], or null when the file is gone, replaced, or shorter. */
    suspend fun read(owner: String, token: String, offset: Long, length: Int): ByteArray? = withContext(Dispatchers.Default) {
        val file = currentFile(owner, token) ?: return@withContext null
        if (offset < 0L || length <= 0 || offset + length > file.length()) return@withContext null
        RandomAccessFile(file, "r").use { raf ->
            val bytes = ByteArray(length)
            raf.seek(offset)
            raf.readFully(bytes)
            bytes
        }
    }

    /** Starts [offer] over: drops any earlier staged bytes and checks the budget before a byte moves. */
    suspend fun beginIncoming(offer: CachedAudioOffer) = incomingMutex.withLock {
        sweepAbandoned()
        validate(offer)
        staged(offer).delete()
        try {
            service.checkRoomForCopy(offer.ownerCanonicalId, offer.sizeBytes)
        } catch (full: IllegalStateException) {
            throw CachedAudioCopyRefused(507, full.message ?: "Offline cache full")
        }
    }

    /**
     * Appends [chunk] at [offset]; the chunk that completes [offer] publishes it once the staged bytes hash
     * to [sha256]. [stillWanted] runs just before publishing, so an unheart during the copy keeps it out.
     * Any refusal deletes the staged bytes.
     */
    suspend fun writeIncoming(
        offer: CachedAudioOffer,
        offset: Long,
        chunk: ByteArray,
        sha256: String,
        stillWanted: () -> Boolean,
    ): CachedAudioWrite = incomingMutex.withLock {
        validate(offer)
        val stage = staged(offer)
        try {
            withContext(Dispatchers.Default) {
                val have = if (stage.exists()) stage.length() else 0L
                if (offset != have) throw CachedAudioCopyRefused(409, "Copy of ${offer.title.ifBlank { offer.ownerCanonicalId }} " +
                    "is out of step (offset $offset, staged $have); transfer again")
                if (chunk.isEmpty() || offset + chunk.size > offer.sizeBytes) {
                    throw CachedAudioCopyRefused(409, "Chunk runs past the ${offer.sizeBytes}-byte file")
                }
                java.io.FileOutputStream(stage, true).use { it.write(chunk) }
            }
            if (stage.length() < offer.sizeBytes) return@withLock CachedAudioWrite.STAGED
            publish(offer, stage, sha256, stillWanted)
        } catch (err: Throwable) {
            stage.delete()
            throw err
        }
    }

    suspend fun discardIncoming(offer: CachedAudioOffer) = incomingMutex.withLock {
        if (isCachedAudioFileToken(offer.fileToken)) staged(offer).delete()
        Unit
    }

    private suspend fun publish(
        offer: CachedAudioOffer,
        stage: File,
        sha256: String,
        stillWanted: () -> Boolean,
    ): CachedAudioWrite {
        val actual = withContext(Dispatchers.Default) { sha256Hex(stage) }
        if (!actual.equals(sha256.trim(), ignoreCase = true)) {
            throw CachedAudioCopyRefused(422, "Copied audio does not match its source (SHA-256 differs); transfer again")
        }
        if (!stillWanted()) {
            stage.delete()
            return CachedAudioWrite.NOT_WANTED
        }
        val target = File(audioDir(), newFileBase() + "." + offer.fileToken.substringAfterLast('.'))
        check(!target.exists()) { "Cache file ${target.name} already exists" }
        if (!stage.renameTo(target)) throw CachedAudioCopyRefused(500, "Could not move copied audio into the cache")
        val entry = HeartedAudioCacheEntry(
            ownerCanonicalId = offer.ownerCanonicalId,
            youtubeVideoId = offer.youtubeVideoId,
            localUri = target.toURI().toASCIIString(),
            qualityTier = offer.qualityTier,
            qualityCodec = offer.qualityCodec,
            qualityBitrateKbps = offer.qualityBitrateKbps,
            qualitySampleRateHz = offer.qualitySampleRateHz,
            sizeBytes = offer.sizeBytes,
            downloadedAtMs = clock(),
            spotifyViaYouTubeMatch = offer.spotifyViaYouTubeMatch,
        )
        val adopted = try {
            service.adoptCopiedFile(entry)
        } catch (err: Throwable) {
            target.delete()
            if (err is IllegalStateException && err !is CachedAudioCopyRefused) throw CachedAudioCopyRefused(507, err.message ?: "Offline cache full")
            throw err
        }
        if (!adopted) target.delete()
        return if (adopted) CachedAudioWrite.PUBLISHED else CachedAudioWrite.ALREADY_CACHED
    }

    private fun validate(offer: CachedAudioOffer) {
        if (!isCachedAudioFileToken(offer.fileToken) || offer.youtubeVideoId.isBlank() || offer.sizeBytes <= 0L) {
            throw CachedAudioCopyRefused(400, "Not a cached YouTube file: ${offer.ownerCanonicalId}")
        }
    }

    /** One staging file per owner and source file, so a retry overwrites rather than piles up. */
    private fun staged(offer: CachedAudioOffer): File =
        File(incomingDir(), sha256Hex((offer.ownerCanonicalId + "\n" + offer.fileToken).encodeToByteArray()).take(32) + ".part")

    /** Copies left by a process that died mid-transfer; an hour is far past any live copy's next chunk. */
    private fun sweepAbandoned() {
        if (swept) return
        swept = true
        val cutoff = clock() - ABANDONED_STAGE_MS
        incomingDir().listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    private fun fileOf(uri: String): File? = runCatching {
        if (uri.startsWith("file:")) File(URI(uri)) else File(uri)
    }.getOrNull()

    private companion object {
        const val ABANDONED_STAGE_MS = 60L * 60L * 1000L
    }
}

private fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
