package com.universalmusic.player.data.library

import com.universalmusic.player.data.playlist.isKainosPlaylistId
import com.universalmusic.player.platform.secureRandomBytes
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Explicit Home pins (Phase 5). Order in [UserLibrarySnapshot.homePins] is display order.
 * Removing a pin never deletes the playlist, album, or folder it points at.
 *
 * ## Home sync eligibility
 * - [HomePinKind.KAINOS_PLAYLIST]: syncable (portable `kainos:playlist:` id; playlist body syncs separately).
 * - [HomePinKind.PROVIDER_PLAYLIST]: syncable when the provider id is portable (Spotify playlist ids);
 *   still scoped to the signed-in Spotify account on each device.
 * - [HomePinKind.ALBUM]: device-local (album group keys include on-device paths).
 * - [HomePinKind.LOCAL_FOLDER]: device-local (SAF URI / absolute path).
 *
 * Wire sync for pins is not required for Phase 5 acceptance; [HomePinKind.canHomeSync] documents
 * the contract for a future `/kainos-sync/v1/pins` (or library-snapshot) merge.
 */
@Serializable
enum class HomePinKind {
    KAINOS_PLAYLIST,
    PROVIDER_PLAYLIST,
    ALBUM,
    LOCAL_FOLDER,
    ;

    /** Whether this pin type may participate in Home LAN sync. */
    val canHomeSync: Boolean
        get() = when (this) {
            KAINOS_PLAYLIST, PROVIDER_PLAYLIST -> true
            ALBUM, LOCAL_FOLDER -> false
        }
}

@Serializable
data class PersistedHomePin(
    /** Stable pin row id (reorder / remove); independent of [targetId]. */
    val id: String,
    val kind: HomePinKind,
    /**
     * Target identity:
     * - Kainos playlist: `kainos:playlist:…`
     * - Provider playlist: `spotify-playlist:{id}` (or other provider canonical)
     * - Album: local album canonical id
     * - Folder: absolute path or SAF tree URI as stored in settings
     */
    val targetId: String,
    /** Cached display title so Home stays readable when the target is temporarily missing. */
    val title: String = "",
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    /**
     * Provider entity id for [HomePinKind.PROVIDER_PLAYLIST] (Spotify playlist id without prefix).
     * Used to load tracks via the Web API.
     */
    val providerEntityId: String? = null,
    /** [com.universalmusic.player.domain.model.ProviderId.name] for provider pins. */
    val provider: String? = null,
)

@OptIn(ExperimentalEncodingApi::class)
fun newHomePinId(): String =
    "pin:" + Base64.UrlSafe.encode(secureRandomBytes(10)).trimEnd('=')

fun PersistedHomePin.isValidShape(): Boolean {
    if (id.isBlank() || targetId.isBlank()) return false
    return when (kind) {
        HomePinKind.KAINOS_PLAYLIST -> isKainosPlaylistId(targetId)
        HomePinKind.PROVIDER_PLAYLIST ->
            targetId.isNotBlank() && (providerEntityId?.isNotBlank() == true || targetId.contains(':'))
        HomePinKind.ALBUM -> targetId.isNotBlank()
        HomePinKind.LOCAL_FOLDER -> targetId.isNotBlank()
    }
}

fun List<PersistedHomePin>.migratedHomePins(): List<PersistedHomePin> =
    filter { it.isValidShape() }
        .distinctBy { it.id }
        .distinctBy { pinKey(it.kind, it.targetId) }

fun pinKey(kind: HomePinKind, targetId: String): String = "${kind.name}|$targetId"

/** Basename / last path segment for folder pin titles. */
fun folderPinDisplayName(folder: String): String {
    val trimmed = folder.trim().trimEnd('/', '\\')
    if (trimmed.isEmpty()) return "Folder"
    // SAF: …/tree/primary%3AMusic%2FPlaylist or document path
    val decoded = trimmed
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace("%2F", "/")
        .replace("%3A", ":")
        .substringAfterLast(':')
        .substringAfterLast('/')
    return decoded.ifBlank { trimmed }.ifBlank { "Folder" }
}

/**
 * Portable pins for a future Home sync merge. Drops device-local kinds and
 * normalizes provider playlist pins to identity + title only.
 */
fun PersistedHomePin.forHomeSync(): PersistedHomePin? {
    if (!kind.canHomeSync) return null
    return when (kind) {
        HomePinKind.KAINOS_PLAYLIST -> copy(
            artworkUrl = artworkUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") },
        )
        HomePinKind.PROVIDER_PLAYLIST -> {
            if (provider != null && provider != "SPOTIFY") return null
            copy(
                artworkUrl = artworkUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") },
            )
        }
        HomePinKind.ALBUM, HomePinKind.LOCAL_FOLDER -> null
    }
}

fun List<PersistedHomePin>.portableForHomeSync(): List<PersistedHomePin> =
    mapNotNull { it.forHomeSync() }.migratedHomePins()

/**
 * Merge remote portable pins into local order. Local device-local pins are preserved.
 * Remote wins on duplicate [pinKey] when [remoteWins] (default: keep local order, append new remote).
 */
fun mergeHomePins(
    local: List<PersistedHomePin>,
    remote: List<PersistedHomePin>,
): List<PersistedHomePin> {
    val localPortable = local.filter { it.kind.canHomeSync }.migratedHomePins()
    val deviceLocal = local.filterNot { it.kind.canHomeSync }.migratedHomePins()
    val remotePortable = remote.mapNotNull { it.forHomeSync() }.migratedHomePins()
    val localKeys = localPortable.map { pinKey(it.kind, it.targetId) }.toSet()
    val mergedPortable = localPortable + remotePortable.filter {
        pinKey(it.kind, it.targetId) !in localKeys
    }
    return (mergedPortable + deviceLocal).migratedHomePins()
}
