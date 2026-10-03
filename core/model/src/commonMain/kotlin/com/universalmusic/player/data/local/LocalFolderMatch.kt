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
