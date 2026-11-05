package com.universalmusic.player.data.local

/**
 * True when a local track [location] lies inside the library root [folder].
 *
 * Desktop roots are plain paths while track locations are percent-encoded `file:` URIs, so both
 * sides are reduced to a decoded path before a directory-boundary prefix check (`/Music/Rock`
 * must not match `/Music/Rock Classics/…`). SAF roots are tree URIs and their tracks are
 * `…/tree/<id>/document/<docId>` URIs built from that tree, so those compare as-is.
 */
fun isLocationInFolder(location: String, folder: String): Boolean {
    // A SAF subfolder is a document URI whose id ends at a directory; its files carry ids below it.
    safDocument(folder)?.let { dir ->
        val doc = safDocument(location) ?: return false
        return doc.tree == dir.tree && (doc.id == dir.id || doc.id.startsWith("${dir.id}/"))
    }
    val root = folderMatchKey(folder) ?: return false
    val path = folderMatchKey(location) ?: return false
    return path == root || path.startsWith("$root/")
}

fun folderMatchKey(raw: String): String? {
    val trimmed = raw.trim().replace('\\', '/').trimEnd('/')
    if (trimmed.isEmpty()) return null
    if (!trimmed.startsWith("file:", ignoreCase = true)) return trimmed
    val afterScheme = trimmed.substring("file:".length)
    val path = when {
        // file:///abs/path
        afterScheme.startsWith("///") -> afterScheme.substring(2)
        // file://host/abs/path (host ignored; local files only)
        afterScheme.startsWith("//") -> afterScheme.indexOf('/', 2).takeIf { it >= 0 }
            ?.let { afterScheme.substring(it) } ?: return null
        else -> afterScheme
    }
    val decoded = percentDecodeUtf8(path)
    // file:///C:/Music → C:/Music so it matches a Windows-style configured root.
    return if (decoded.length > 2 && decoded[0] == '/' && decoded[2] == ':') decoded.substring(1) else decoded
}

/** Strict RFC 3986 percent-decoding; `+` stays literal because it is valid in file names. */
fun percentDecodeUtf8(value: String): String {
    if ('%' !in value) return value
    val bytes = ArrayList<Byte>(value.length)
    val out = StringBuilder(value.length)
    fun flush() {
        if (bytes.isEmpty()) return
        out.append(bytes.toByteArray().decodeToString())
        bytes.clear()
    }
    var i = 0
    while (i < value.length) {
        val c = value[i]
        val hi = if (c == '%' && i + 2 <= value.lastIndex) value[i + 1].digitToIntOrNull(16) else null
        val lo = if (hi != null) value[i + 2].digitToIntOrNull(16) else null
        if (hi != null && lo != null) {
            bytes += ((hi shl 4) or lo).toByte()
            i += 3
        } else {
            flush()
            out.append(c)
            i += 1
        }
    }
    flush()
    return out.toString()
}

/**
 * Folder names from library [root] down to the directory that holds the file at [location],
 * or null when the file lies outside [root]. A SAF provider whose document ids are not paths
 * gives no hierarchy, so its files sit directly in the root.
 */
fun localFolderSegments(location: String, root: String): List<String>? {
    if (!isLocationInFolder(location, root)) return null
    val relative = when (val doc = safDocument(location)) {
        null -> folderMatchKey(location)!!.removePrefix(folderMatchKey(root)!!)
        else -> {
            val prefix = safTreeDocumentId(root)?.let(::safChildPrefix) ?: return emptyList()
            if (!doc.id.startsWith(prefix)) return emptyList()
            doc.id.substring(prefix.length)
        }
    }
    return relative.split('/').dropLast(1).filter { it.isNotEmpty() }
}

/**
 * The folder [segments] below library [root], in the form [isLocationInFolder] and Home folder
 * pins take: a plain path on desktop, a SAF document URI inside the root's tree on Android.
 */
fun localSubfolderId(root: String, segments: List<String>): String {
    if (segments.isEmpty()) return root
    val path = segments.joinToString("/")
    val treeId = safTreeDocumentId(root) ?: return root.trimEnd('/', '\\') + "/" + path
    return root.trimEnd('/') + "/document/" + percentEncodeUriComponent(safChildPrefix(treeId) + path)
}

private class SafDocument(val tree: String, val id: String)

/** `content://…/tree/<tree>/document/<id>` split into its tree URI and decoded document id. */
private fun safDocument(uri: String): SafDocument? {
    val marker = uri.indexOf("/document/")
    if (marker < 0 || !uri.startsWith("content:") || "/tree/" !in uri.substring(0, marker)) return null
    return SafDocument(uri.substring(0, marker), percentDecodeUtf8(uri.substring(marker + "/document/".length)))
}

/** Decoded document id of a SAF tree root URI (`…/tree/primary%3AMusic` → `primary:Music`). */
private fun safTreeDocumentId(root: String): String? {
    if (!root.startsWith("content:") || "/document/" in root) return null
    val encoded = root.substringAfter("/tree/", "").substringBefore('/')
    return encoded.takeIf { it.isNotEmpty() }?.let(::percentDecodeUtf8)
}

/** External storage ids are `volume:path`; the volume root `primary:` has children `primary:Music`. */
private fun safChildPrefix(treeId: String): String = if (treeId.endsWith(':') || treeId.endsWith('/')) treeId else "$treeId/"

/** Percent-encodes like Android's `Uri.encode`, which builds SAF document URIs. */
private fun percentEncodeUriComponent(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val c = byte.toInt() and 0xFF
        if (c < 0x80 && (c.toChar().isLetterOrDigit() || c.toChar() in "_-!.~'()*")) {
            append(c.toChar())
        } else {
            append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
        }
    }
}

private const val HEX = "0123456789ABCDEF"
