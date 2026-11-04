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
 * Home sync exchanges the portable kinds on `/kainos-sync/v3/pins`; see [mergeHomePinsSync].
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
    /** Last pin of a portable kind; null for device-local kinds and pins made before Home sync carried pins. */
    val syncStamp: HomePinStamp? = null,
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

/** Portable form of a syncable pin, or null for device-local kinds and non-Spotify provider pins. */
fun PersistedHomePin.forHomeSync(): PersistedHomePin? = when (kind) {
    HomePinKind.KAINOS_PLAYLIST -> this
    HomePinKind.PROVIDER_PLAYLIST -> takeIf { isSpotifyPlaylistPin() }
    HomePinKind.ALBUM, HomePinKind.LOCAL_FOLDER -> null
}?.copy(artworkUrl = artworkUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") })

fun List<PersistedHomePin>.portableForHomeSync(): List<PersistedHomePin> =
    mapNotNull { it.forHomeSync() }.migratedHomePins()

fun PersistedHomePin.isSpotifyPlaylistPin(): Boolean =
    kind == HomePinKind.PROVIDER_PLAYLIST && (provider == "SPOTIFY" || (provider == null && targetId.startsWith("spotify")))
