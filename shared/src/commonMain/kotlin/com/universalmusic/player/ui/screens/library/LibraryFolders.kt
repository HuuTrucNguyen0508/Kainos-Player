package com.universalmusic.player.ui.screens

import com.universalmusic.player.data.library.folderPinDisplayName
import com.universalmusic.player.data.local.localFolderSegments
import com.universalmusic.player.data.local.localSubfolderId
import com.universalmusic.player.data.sync.basenameFromLocalLocation
import com.universalmusic.player.domain.matching.FuzzyMatcher
import com.universalmusic.player.domain.matching.TrackNormalizer
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track

/**
 * One directory of a configured music folder. [id] is what `isLocationInFolder` and Home folder
 * pins take. [tracks] are the files directly inside, ordered by file name like a file browser.
 */
internal class LibraryFolder(
    val id: String,
    val name: String,
    val folders: List<LibraryFolder>,
    val tracks: List<Track>,
) {
    /** Everything below, in browsing order: this folder's files, then each subfolder's. */
    val allTracks: List<Track> by lazy { tracks + folders.flatMap { it.allTracks } }
}

/**
 * Every configured root as a folder tree of the local [tracks] beneath it. Roots stay listed
 * when empty so a folder that scanned nothing is still visible; subfolders exist only when a
 * track lives in them. A track under two nested roots appears under both, like Home pins.
 */
internal fun libraryFolderTree(roots: List<String>, tracks: List<Track>): List<LibraryFolder> {
    val located = tracks.mapNotNull { track ->
        val location = (track.sourceFor(ProviderId.LOCAL)?.handle as? PlaybackHandle.Url)?.url
        location?.let { track to it }
    }
    return roots.distinct().map { root ->
        val placed = located.mapNotNull { (track, location) ->
            localFolderSegments(location, root)?.let { Placed(track, location, it) }
        }
        buildFolder(root, emptyList(), folderPinDisplayName(root), placed)
    }
}

private class Placed(val track: Track, val location: String, val segments: List<String>)

private fun buildFolder(root: String, path: List<String>, name: String, placed: List<Placed>): LibraryFolder {
    val depth = path.size
    val (here, below) = placed.partition { it.segments.size == depth }
    val children = below
        .groupBy { it.segments[depth] }
        .toList()
        .sortedWith { a, b -> naturalCompare(a.first, b.first) }
        .map { (child, inside) -> buildFolder(root, path + child, child, inside) }
    val files = here
        .map { (basenameFromLocalLocation(it.location) ?: it.track.title.lowercase()) to it.track }
        .sortedWith { a, b -> naturalCompare(a.first, b.first) }
        .map { it.second }
    return LibraryFolder(localSubfolderId(root, path), name, children, files)
}

/** The folders from a root down to [id], or null when no folder in the tree has that id. */
internal fun List<LibraryFolder>.trailTo(id: String?): List<LibraryFolder>? {
    if (id == null) return null
    for (folder in this) {
        if (folder.id == id) return listOf(folder)
        folder.folders.trailTo(id)?.let { return listOf(folder) + it }
    }
    return null
}

/** What a Folders view shows for a search: matching folder names or folders holding a matching track. */
internal fun LibraryFolder.visibleFor(needle: String, matchingTrackIds: Set<String>): Pair<List<LibraryFolder>, List<Track>> {
    if (needle.isEmpty()) return folders to tracks
    return folders.filter { folder ->
        FuzzyMatcher.matches(needle, TrackNormalizer.fold(folder.name)) ||
            folder.allTracks.any { it.canonicalId in matchingTrackIds }
    } to tracks.filter { it.canonicalId in matchingTrackIds }
}

/** Case-insensitive order where digit runs compare by value, so "2 Song" sorts before "10 Song". */
internal fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            val endA = a.indexOfFirstFrom(i) { !it.isDigit() }
            val endB = b.indexOfFirstFrom(j) { !it.isDigit() }
            val numA = a.substring(i, endA).trimStart('0')
            val numB = b.substring(j, endB).trimStart('0')
            if (numA.length != numB.length) return numA.length - numB.length
            val byValue = numA.compareTo(numB)
            if (byValue != 0) return byValue
            i = endA
            j = endB
        } else {
            val byChar = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (byChar != 0) return byChar
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}

private inline fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
    for (k in start until length) if (predicate(this[k])) return k
    return length
}
