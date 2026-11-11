package com.universalmusic.player.data.local

/** Title and artist guessed from a file name, for files without tags. */
data class FilenameTags(val title: String, val artist: String?)

/**
 * Clean a downloaded file's name into a title and maybe an artist. Shared by the desktop scan and
 * Android SAF. Rules follow the echo gateway's cleaner (`tools/echo-gateway/.../scan.py`):
 * drop a `y2mate.com - ` prefix and a `_<YouTube id>_320kbps` suffix, then split
 * `Artist - Title`. Also: en/em dashes count as the separator, underscores become spaces,
 * `01 - ` style track numbers and `(Official Audio)` / `(Lyrics)` / `[CC]` noise are removed.
 */
fun filenameTags(fileName: String): FilenameTags {
    val stem = fileName.substringAfterLast('/').let { name ->
        val dot = name.lastIndexOf('.')
        if (dot > 0) name.substring(0, dot) else name
    }
    val stripped = stem
        .replace(DOWNLOADER_PREFIX, "")
        .replace(DOWNLOADER_SUFFIX, "")
        .replace(DASH_SEPARATOR, " - ")
        .humanized()
        .replace(NOISE, "")
        .replace(TRACK_NUMBER_PREFIX, "")
        .humanized()
    val separator = stripped.indexOf(" - ")
    val artist = stripped.takeIf { separator > 0 }?.substring(0, separator)?.trim()?.takeIf(String::isNotEmpty)
    val title = (if (artist != null) stripped.substring(separator + 3) else stripped)
        .replace(TRACK_NUMBER_PREFIX, "")
        .replace(DANGLING_SEPARATOR, "")
        .humanized()
    return FilenameTags(
        title = title.ifEmpty { stripped.ifEmpty { stem.humanized() } },
        artist = artist?.takeIf { title.isNotEmpty() },
    )
}

/** "3", "03", "3/12" → 3. Zero, negative or unreadable → null. */
fun parseTagPosition(raw: String?): Int? = raw
    ?.trim()
    ?.substringBefore('/')
    ?.trim()
    ?.toIntOrNull()
    ?.takeIf { it > 0 }

/** "2021", "1998-11-10", "20210709T000000.000Z" → the year; anything else → null. */
fun parseTagYear(raw: String?): Int? = raw
    ?.trim()
    ?.let(YEAR::find)
    ?.value
    ?.toIntOrNull()
    ?.takeIf { it in 1000..2999 }

fun cleanTagText(raw: String?): String? =
    raw?.replace(REPEATED_WHITESPACE, " ")?.trim()?.takeIf(String::isNotEmpty)

private fun String.humanized(): String = replace('_', ' ').replace(REPEATED_WHITESPACE, " ").trim()

private val DOWNLOADER_PREFIX = Regex("""^y2mate\.[a-z]+\s*-\s*""", RegexOption.IGNORE_CASE)
private val DOWNLOADER_SUFFIX = Regex("""(?:_[A-Za-z0-9_-]{11})?_\d+kbps$""", RegexOption.IGNORE_CASE)

/** En dash, em dash, horizontal bar and full-width hyphen between spaces mean "Artist - Title". */
private val DASH_SEPARATOR = Regex("""\s+[–—―－]\s+""")

/** "01 - ", "01. ", "1-03 ", "07_": a leading track (and disc) number with a separator. */
private val TRACK_NUMBER_PREFIX = Regex("""^(?:\d{1,2}\s*[.\-]\s+|\d{1,2}-\d{1,2}[\s._]+|\d{2}[\s._]+)""")

private val NOISE = Regex(
    """\s*[(\[](?:official\s+)?(?:music\s+|lyrics?\s+)?(?:video|audio|lyrics?|visualizer|cc|hd|hq|4k)[)\]]""",
    RegexOption.IGNORE_CASE,
)

/** A separator left at either end once the rest was stripped ("Vol.3 -"), not "-ginkiha mix-". */
private val DANGLING_SEPARATOR = Regex("""^\s*-\s+|\s+-\s*$""")

private val YEAR = Regex("""^\d{4}""")
private val REPEATED_WHITESPACE = Regex("""\s+""")
