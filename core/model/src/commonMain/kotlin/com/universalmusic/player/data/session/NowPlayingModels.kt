package com.universalmusic.player.data.session

import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.playlist.forPlaylistSync
import com.universalmusic.player.data.sync.LOCALFILE_HEART_PREFIX
import com.universalmusic.player.data.sync.localKeyHeartContentKey
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.platform.sha256Bytes
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.jvm.JvmInline

/**
 * PlayerSession's synchronous guard, advanced on the session thread by every recorded change.
 * It lives as long as one session and is never persisted or compared with [PublicationRevision].
 */
@JvmInline
value class SessionRevision(val value: Long)

/** Durable revision SQLite allocates in the transaction that commits this device's local state. */
@JvmInline
value class PublicationRevision(val value: Long)

/** Whether the restored queue came from this device or was imported from a peer and not yet touched. */
enum class PublicationOrigin { LOCAL, REMOTE_SUPPRESSED }

@JvmInline
value class QueueHash private constructor(val hex: String) {
    companion object {
        private val HEX = Regex("[0-9a-f]{64}")

        fun parse(hex: String): QueueHash {
            require(HEX.matches(hex)) { "Invalid queue hash" }
            return QueueHash(hex)
        }

        internal fun of(entries: List<Pair<String, String>>): QueueHash =
            QueueHash(sha256Bytes(canonicalQueueJson(entries).encodeToByteArray()).toHex())
    }
}

enum class ProjectionFailure { UNSUPPORTED_TRACK, INVALID_ID, DUPLICATE_ITEM_ID }

/** A track another device can resolve: provider ids or a portable local identity, never a location. */
@ConsistentCopyVisibility
data class PortableListeningTrack private constructor(val track: PersistedTrack) {
    val identity: String get() = track.canonicalId

    companion object {
        fun project(track: PersistedTrack): PortableListeningTrack? = track.forPlaylistSync()?.let(::parse)

        /** Accepts only an already-portable track; it is never rewritten here. */
        fun parse(track: PersistedTrack): PortableListeningTrack? =
            if (track.isPortableListeningTrack()) PortableListeningTrack(track) else null
    }
}

data class PortableListeningEntry(val itemId: String, val track: PortableListeningTrack)

sealed interface QueueProjection {
    data class Complete(val queue: PortableListeningQueue) : QueueProjection
    data class Unsupported(val itemId: String, val reason: ProjectionFailure) : QueueProjection
}

@ConsistentCopyVisibility
data class PortableListeningQueue private constructor(
    val entries: List<PortableListeningEntry>,
    val hash: QueueHash,
) {
    fun encodeJson(): String = portableJson.encodeToString(
        ListSerializer(PersistedQueueItem.serializer()),
        entries.map { PersistedQueueItem(it.itemId, it.track.track) },
    )

    companion object {
        /** Projects the whole queue or nothing: an unsupported entry is reported, never dropped. */
        fun project(items: List<PersistedQueueItem>): QueueProjection {
            val entries = ArrayList<PortableListeningEntry>(items.size)
            for (item in items) {
                val track = PortableListeningTrack.project(item.track)
                    ?: return QueueProjection.Unsupported(item.id, ProjectionFailure.UNSUPPORTED_TRACK)
                entries += PortableListeningEntry(item.id, track)
            }
            return of(entries)
        }

        fun of(entries: List<PortableListeningEntry>): QueueProjection {
            val seen = HashSet<String>(entries.size * 2)
            for (entry in entries) {
                if (entry.itemId.isBlank() || !entry.itemId.isWellFormed() || !entry.track.identity.isWellFormed()) {
                    return QueueProjection.Unsupported(entry.itemId, ProjectionFailure.INVALID_ID)
                }
                if (!seen.add(entry.itemId)) return QueueProjection.Unsupported(entry.itemId, ProjectionFailure.DUPLICATE_ITEM_ID)
            }
            val hash = QueueHash.of(entries.map { it.itemId to it.track.identity })
            return QueueProjection.Complete(PortableListeningQueue(entries.toList(), hash))
        }

        /** Decodes a stored payload and rejects it unless every entry is portable and the hash matches. */
        fun decode(json: String, expected: QueueHash): PortableListeningQueue {
            val items = portableJson.decodeFromString(ListSerializer(PersistedQueueItem.serializer()), json)
            val entries = items.map { item ->
                val track = requireNotNull(PortableListeningTrack.parse(item.track)) { "Stored queue entry ${item.id} is not portable" }
                PortableListeningEntry(item.id, track)
            }
            val queue = (of(entries) as? QueueProjection.Complete)?.queue
            require(queue != null && queue.hash == expected) { "Stored queue does not match its hash" }
            return queue
        }
    }
}

/** One device's listening state: the current queue entry belongs to [queue]. */
@ConsistentCopyVisibility
data class ListeningState private constructor(
    val current: PortableListeningEntry?,
    val positionMs: Long,
    val positionAtMs: Long,
    val isPlaying: Boolean,
    val queue: PortableListeningQueue,
) {
    companion object {
        fun create(
            queue: PortableListeningQueue,
            currentItemId: String?,
            positionMs: Long,
            positionAtMs: Long,
            isPlaying: Boolean,
        ): ListeningState {
            require(positionMs >= 0) { "Negative position" }
            val current = currentItemId?.let { id ->
                requireNotNull(queue.entries.firstOrNull { it.itemId == id }) { "Current entry $id is not in the queue" }
            }
            return ListeningState(current, positionMs, positionAtMs, isPlaying, queue)
        }
    }
}

data class LocalListeningPublication(
    val revision: PublicationRevision,
    val lastActiveAtMs: Long,
    val state: ListeningState,
)

/** What this device may export now. A stale publication is never offered once a newer capture failed. */
sealed interface LocalListeningExport {
    data class Available(val publication: LocalListeningPublication) : LocalListeningExport
    data class Unavailable(
        val revision: PublicationRevision,
        val lastActiveAtMs: Long,
        val reason: ProjectionFailure,
    ) : LocalListeningExport
}

/** The local half of a commit; [lastActiveAtMs] null keeps the durable activity time. */
sealed interface LocalListeningUpdate {
    val sequence: Long
    val lastActiveAtMs: Long?

    data class Publish(
        override val sequence: Long,
        override val lastActiveAtMs: Long?,
        val state: ListeningState,
    ) : LocalListeningUpdate

    data class Unavailable(
        override val sequence: Long,
        override val lastActiveAtMs: Long?,
        val reason: ProjectionFailure,
    ) : LocalListeningUpdate
}

/**
 * One ordered write from one session's worker. [writerId] and the sequences let a retry after an
 * ambiguous commit skip what already landed instead of replaying it over another writer's newer row.
 */
data class ListeningCommit(
    val writerId: String,
    val sequence: Long,
    val snapshot: SessionSnapshot,
    val origin: PublicationOrigin,
    val local: LocalListeningUpdate?,
    val committedAtMs: Long,
)

data class ListeningRestore(val snapshot: SessionSnapshot, val origin: PublicationOrigin)

interface ListeningStore {
    suspend fun readRestore(): ListeningRestore
    suspend fun commit(commit: ListeningCommit)
    suspend fun readLocalExport(): LocalListeningExport?
}

/**
 * Canonical bytes for the queue hash: a JSON array of `[itemId, identity]` pairs, no whitespace,
 * control characters as lowercase `\u00xx`, quote and backslash escaped, everything else literal.
 */
fun canonicalQueueJson(entries: List<Pair<String, String>>): String = buildString {
    append('[')
    entries.forEachIndexed { index, (itemId, identity) ->
        if (index > 0) append(',')
        append('[')
        appendCanonicalString(itemId)
        append(',')
        appendCanonicalString(identity)
        append(']')
    }
    append(']')
}

private fun StringBuilder.appendCanonicalString(value: String) {
    require(value.isWellFormed()) { "Unpaired UTF-16 surrogate" }
    append('"')
    for (char in value) {
        when {
            char == '"' -> append("\\\"")
            char == '\\' -> append("\\\\")
            char.code < 0x20 -> append("\\u00").append(HEX_DIGITS[char.code shr 4]).append(HEX_DIGITS[char.code and 0xf])
            else -> append(char)
        }
    }
    append('"')
}

private fun String.isWellFormed(): Boolean {
    var index = 0
    while (index < length) {
        val char = this[index]
        when {
            char.isHighSurrogate() -> {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                index += 2
            }
            char.isLowSurrogate() -> return false
            else -> index += 1
        }
    }
    return true
}

private const val HEX_DIGITS = "0123456789abcdef"

private fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) {
        val value = byte.toInt() and 0xff
        append(HEX_DIGITS[value shr 4]).append(HEX_DIGITS[value and 0xf])
    }
}

private val portableJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private const val SPOTIFY_PREFIX = "spotify:"
private const val YOUTUBE_PREFIX = "yt:"

private fun PersistedTrack.isPortableListeningTrack(): Boolean {
    if (cachedAtMs != 0L || sources.isEmpty() || sources.any { it.localLocation != null }) return false
    if (artworkUrl != null && !artworkUrl.startsWith("https://") && !artworkUrl.startsWith("http://")) return false
    val contentKey = localKeyHeartContentKey(canonicalId)
    return when {
        contentKey != null -> localContentKey == contentKey && hasOnlyLocalSource { it.isPortableBasename() }
        canonicalId.startsWith(LOCALFILE_HEART_PREFIX) -> {
            val basename = canonicalId.removePrefix(LOCALFILE_HEART_PREFIX)
            localContentKey == null && basename.isPortableBasename() && hasOnlyLocalSource { it == basename }
        }
        canonicalId.startsWith(SPOTIFY_PREFIX) -> isPortableProviderTrack(canonicalId.removePrefix(SPOTIFY_PREFIX))
        canonicalId.startsWith(YOUTUBE_PREFIX) -> isPortableProviderTrack(canonicalId.removePrefix(YOUTUBE_PREFIX))
        else -> false
    }
}

private fun PersistedTrack.hasOnlyLocalSource(providerTrackId: (String) -> Boolean): Boolean =
    sources.size == 1 && sources[0].provider == ProviderId.LOCAL.name && providerTrackId(sources[0].providerTrackId)

private fun PersistedTrack.isPortableProviderTrack(id: String): Boolean =
    localContentKey == null && id.isProviderToken() && sources.all { source ->
        (source.provider == ProviderId.SPOTIFY.name || source.provider == ProviderId.YOUTUBE_MUSIC.name) &&
            source.providerTrackId.isProviderToken()
    }

/** Provider ids are opaque tokens; anything with a separator could be a URL or a path. */
private fun String.isProviderToken(): Boolean =
    isNotEmpty() && none { it == '/' || it == '\\' || it.isWhitespace() || it.isISOControl() }

/** Any file name is fine, punctuation and colons included, as long as it cannot address a path. */
private fun String.isPortableBasename(): Boolean =
    isNotBlank() && this != "." && this != ".." && none { it == '/' || it == '\\' || it.isISOControl() }
