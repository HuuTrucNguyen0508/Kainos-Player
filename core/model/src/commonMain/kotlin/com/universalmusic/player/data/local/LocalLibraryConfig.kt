package com.universalmusic.player.data.local

/**
 * How Settings → Music folders is interpreted for scanning.
 *
 * - [USE_DEFAULTS]: never configured (or reset); platform default applies (desktop ~/Music).
 * - [EXPLICIT]: user picked folders; [folders] may be empty (scan nothing — do not reintroduce defaults).
 */
enum class LocalLibraryRootMode {
    USE_DEFAULTS,
    EXPLICIT,
}

data class LocalLibraryScanConfig(
    val mode: LocalLibraryRootMode = LocalLibraryRootMode.USE_DEFAULTS,
    val folders: List<String> = emptyList(),
    /** Android: also index MediaStore music, deduped against SAF roots when both contribute. */
    val includeMediaStore: Boolean = true,
) {
    fun effectiveFolders(defaultFolder: String?): List<String> = when (mode) {
        LocalLibraryRootMode.USE_DEFAULTS ->
            listOfNotNull(defaultFolder?.takeIf { it.isNotBlank() })
        LocalLibraryRootMode.EXPLICIT ->
            folders.map(String::trim).filter(String::isNotEmpty).distinct()
    }
}

fun localAlbumCanonicalId(
    albumTitle: String,
    artists: List<String>,
    albumGroupKey: String,
): String {
    val album = albumTitle.trim().lowercase()
    val artist = artists.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.sorted().joinToString("|")
        .ifEmpty { "unknown-artist" }
    val group = albumGroupKey.trim().lowercase().ifEmpty { "ungrouped" }
    return "local-album:${artist}|${album}|${group.hashCode().toUInt().toString(16)}"
}

fun localArtistCanonicalId(name: String): String =
    "local-artist:${name.trim().lowercase()}"

/**
 * Artwork resolution order for local files (shared policy):
 * 1. Embedded cover extracted to a file/content URI (when available)
 * 2. Sidecar in the album folder: cover.jpg / cover.png / folder.jpg / folder.png (case-insensitive)
 * 3. Platform album art (e.g. MediaStore albumart URI)
 * 4. None
 *
 * Extracted/sidecar images should be bounded (see desktop cache helpers).
 */
object LocalArtworkPolicy {
    val SIDECAR_NAMES = listOf(
        "cover.jpg", "cover.jpeg", "cover.png", "cover.webp",
        "folder.jpg", "folder.jpeg", "folder.png", "folder.webp",
    )

    /** Max bytes kept for a cached embedded/sidecar image used by UI and system controls. */
    const val MAX_ARTWORK_BYTES: Long = 2L * 1024L * 1024L
}
