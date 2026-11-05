package com.universalmusic.player.platform

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Private, per-process journal. No port, credentials, metadata, or shared receiver state. */
internal class LibrespotEventBridge(deliver: suspend (SpotifyReceiverEvent) -> Unit) : AutoCloseable {
    // librespot 0.8.0 splits --onevent on whitespace, so keep the executable path space-free.
    private val directory = Files.createTempDirectory(
        java.nio.file.Path.of("/tmp"), "kainos-librespot-events-",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
    )
    private val journal = directory.resolve("events")
    val program = directory.resolve("event.sh")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reader: kotlinx.coroutines.Job
    val isOpen: Boolean get() = reader.isActive

    init {
        Files.createFile(journal, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        // Event names and variables: librespot v0.8.0/src/player_event_handler.rs.
        Files.writeString(program, """
            #!/bin/sh
            case "${'$'}PLAYER_EVENT" in
                playing|paused|seeked|position_correction|end_of_track|stopped|unavailable)
                    printf '%s\t%s\t%s\n' "${'$'}PLAYER_EVENT" "${'$'}TRACK_ID" "${'$'}{POSITION_MS:-}" >> '$journal'
                    ;;
            esac
        """.trimIndent() + "\n")
        Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwx------"))
        reader = scope.launch {
            RandomAccessFile(journal.toFile(), "r").use { file ->
                val line = StringBuilder()
                var overflow = false
                while (isActive) {
                    val next = file.read()
                    when {
                        next < 0 -> delay(25)
                        next == '\n'.code -> {
                            if (!overflow) parseLibrespotEvent(line.toString())?.let { deliver(it) }
                            line.clear()
                            overflow = false
                        }
                        line.length < 256 && !overflow -> line.append(next.toChar())
                        else -> overflow = true
                    }
                }
            }
        }
    }

    override fun close() {
        scope.cancel()
        directory.toFile().deleteRecursively()
    }
}

internal fun parseLibrespotEvent(line: String): SpotifyReceiverEvent? {
    val fields = line.split('\t')
    if (fields.size != 3 || !fields[1].matches(Regex("[0-9A-Za-z]{22}"))) return null
    val kind = when (fields[0]) {
        "playing" -> SpotifyReceiverEvent.Kind.PLAYING
        "paused" -> SpotifyReceiverEvent.Kind.PAUSED
        "seeked" -> SpotifyReceiverEvent.Kind.SEEKED
        "position_correction" -> SpotifyReceiverEvent.Kind.POSITION_CORRECTION
        "end_of_track" -> SpotifyReceiverEvent.Kind.END_OF_TRACK
        "stopped" -> SpotifyReceiverEvent.Kind.STOPPED
        "unavailable" -> SpotifyReceiverEvent.Kind.UNAVAILABLE
        else -> return null
    }
    val position = fields[2].takeIf(String::isNotEmpty)?.toLongOrNull()
    if (kind in setOf(SpotifyReceiverEvent.Kind.PLAYING, SpotifyReceiverEvent.Kind.PAUSED,
            SpotifyReceiverEvent.Kind.SEEKED, SpotifyReceiverEvent.Kind.POSITION_CORRECTION) &&
        (position == null || position < 0)
    ) return null
    return SpotifyReceiverEvent(kind, fields[1], position)
}
