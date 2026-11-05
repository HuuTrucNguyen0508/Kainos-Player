package com.universalmusic.player.platform

import com.universalmusic.player.domain.playback.ReplayGain
import com.universalmusic.player.domain.playback.VolumeLevelling
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal interface MpvEventStream : AutoCloseable {
    fun readLine(): String?
}

internal interface DesktopPlaybackRuntime {
    fun loadEntry(command: String, socket: Path): Long? {
        if (!sendIpc(command, socket)) return null
        return runCatching {
            val playlist = Json.parseToJsonElement(readProperty("playlist", socket) ?: return null)
                as kotlinx.serialization.json.JsonArray
            playlist.last().jsonObject["id"]?.jsonPrimitive?.content?.toLongOrNull()
        }.getOrNull()
    }
    fun canPrepare(current: String, next: String): Boolean = true
    val supportsEvents: Boolean get() = false
    fun openEvents(socket: Path): MpvEventStream? = null
    fun findOnPath(name: String): String?
    fun probeDurationMs(url: String): Long?
    /** ReplayGain that ffmpeg's `volume=replaygain=track` filter will apply to this local file, or null. */
    fun probeReplayGain(url: String): ReplayGain? = null
    fun createIpcPath(): Path
    fun startProcess(command: List<String>): Process
    fun waitForIpc(socket: Path)
    fun sendIpc(commandArrayJson: String, socket: Path): Boolean
    fun readProperty(name: String, socket: Path): String? = null
    fun deleteIpcPath(path: Path)
}

internal object SystemDesktopPlaybackRuntime : DesktopPlaybackRuntime {
    override val supportsEvents = true
    override fun canPrepare(current: String, next: String): Boolean {
        fun format(url: String): String? {
            val path = mediaPathForProbe(url) ?: return null
            if (!Files.isRegularFile(Path.of(path)) || !Files.isReadable(Path.of(path))) return null
            val header = Files.newInputStream(Path.of(path)).use { it.readNBytes(42) }
            if (header.size == 42 && header.copyOfRange(0, 4).decodeToString() == "fLaC") {
                var packed = 0L
                for (i in 18..25) packed = (packed shl 8) or (header[i].toLong() and 0xff)
                return (packed ushr 36).toString() // sample rate, channels and bit depth, freshly read
            }
            val probe = findOnPath("ffprobe") ?: return null
            return ProcessBuilder(probe, "-v", "error", "-select_streams", "a:0",
                "-show_entries", "stream=sample_rate,channels,sample_fmt", "-of", "csv=p=0", path)
                .readOutputWithin(5_000)?.trim()?.takeIf { it.isNotEmpty() }

        }
        return runCatching { val first = format(current); first != null && first == format(next) }.getOrDefault(false)
    }
    override fun openEvents(socket: Path): MpvEventStream {
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        channel.connect(UnixDomainSocketAddress.of(socket))
        val reader = java.nio.channels.Channels.newInputStream(channel).bufferedReader()
        return object : MpvEventStream {
            override fun readLine(): String? = reader.readLine()
            override fun close() { channel.close() }
        }
    }

    override fun findOnPath(name: String): String? {
        val path = System.getenv("PATH") ?: return null
        return path.split(':').firstOrNull { dir ->
            java.io.File(dir, name).canExecute()
        }?.let { java.io.File(it, name).absolutePath }
    }

    override fun probeDurationMs(url: String): Long? {
        val media = mediaPathForProbe(url) ?: url
        if (url.startsWith("file:") || !url.contains("://")) {
            readLocalFlacDurationMs(media)?.let { return it }
        }
        val ffprobe = findOnPath("ffprobe") ?: return null
        return runCatching {
            val output = ProcessBuilder(
                ffprobe,
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                media,
            ).readOutputWithin(5_000)?.trim() ?: return null
            val seconds = output.lineSequence().firstOrNull()?.toDoubleOrNull() ?: return null
            (seconds * 1000.0).toLong().takeIf { it > 0L }
        }.getOrNull()
    }

    override fun probeReplayGain(url: String): ReplayGain? {
        if (!isLocalMediaUrl(url)) return null
        val media = mediaPathForProbe(url) ?: return null
        val ffprobe = findOnPath("ffprobe") ?: return null
        val output = runCatching {
            ProcessBuilder(
                ffprobe, "-v", "error", "-select_streams", "a:0",
                "-show_entries", "stream_side_data=side_data_type:stream_tags:format_tags",
                "-of", "json", media,
            ).readOutputWithin(5_000)
        }.getOrNull() ?: return null
        return replayGainFromFfprobe(output)
    }

    /** Read fresh STREAMINFO duration directly, avoiding a subprocess for local FLACs.
     * RFC 9639 section 8.2: https://www.rfc-editor.org/rfc/rfc9639.html#section-8.2
     */
    private fun readLocalFlacDurationMs(media: String): Long? = runCatching {
        val path = Path.of(media)
        if (!Files.isRegularFile(path)) return null
        java.io.RandomAccessFile(path.toFile(), "r").use { file ->
            if (file.length() < 44) return null
            val header = ByteArray(42)
            file.readFully(header)
            if (header.copyOfRange(0, 4).decodeToString() != "fLaC") return null
            if ((header[4].toInt() and 0x7f) != 0 ||
                header[5] != 0.toByte() || header[6] != 0.toByte() || header[7] != 34.toByte()
            ) return null
            fun unsigned16(offset: Int) =
                ((header[offset].toInt() and 0xff) shl 8) or (header[offset + 1].toInt() and 0xff)
            val minBlock = unsigned16(8)
            val maxBlock = unsigned16(10)
            var packed = 0L
            for (index in 18..25) packed = (packed shl 8) or (header[index].toLong() and 0xff)
            val sampleRate = packed ushr 44
            val channels = ((packed ushr 41) and 7) + 1
            val bitsPerSample = ((packed ushr 36) and 31) + 1
            val samples = packed and ((1L shl 36) - 1)
            if (sampleRate !in 1L..655350L || channels !in 1L..8L ||
                bitsPerSample !in 4L..32L || minBlock < 16 || minBlock > maxBlock || samples == 0L
            ) return null
            var last = header[4].toInt() and 0x80 != 0
            while (!last) {
                // Seek over payloads (including large pictures); read only block headers.
                if (file.filePointer + 4 > file.length()) return null
                val type = file.readUnsignedByte()
                if ((type and 0x7f) !in 1..6) return null
                last = type and 0x80 != 0
                val length = (file.readUnsignedByte() shl 16) or
                    (file.readUnsignedByte() shl 8) or file.readUnsignedByte()
                val next = file.filePointer + length
                if (next + 2 > file.length()) return null
                file.seek(next)
            }
            if (file.read() != 0xff || file.read() !in 0xf8..0xf9) return null
            ((samples * 1_000) / sampleRate).takeIf { it > 0 }
        }
    }.getOrNull()

    private fun mediaPathForProbe(url: String): String? = runCatching {
        when {
            url.startsWith("file:") -> java.nio.file.Paths.get(java.net.URI(url)).toString()
            else -> url
        }
    }.getOrNull()

    override fun createIpcPath(): Path =
        Files.createTempFile("kainos-mpv-", ".sock").also { Files.deleteIfExists(it) }

    override fun startProcess(command: List<String>): Process =
        // The JVM owns stdin. mpv exits on EOF, including after SIGKILL of the JVM.
        ProcessBuilder(command + "--input-ipc-client=fd://0").redirectErrorStream(true).start()

    override fun waitForIpc(socket: Path) {
        val deadline = System.currentTimeMillis() + 2_500
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(socket) && sendIpc("""["get_property","pause"]""", socket)) return
            Thread.sleep(40)
        }
    }

    override fun sendIpc(commandArrayJson: String, socket: Path): Boolean =
        // Drain one response line so the socket stays healthy; a timeout counts as a failed IPC.
        when (val reply = exchange(socket, """{"command":$commandArrayJson}""")) {
            is IpcReply.Line -> mpvCommandSucceeded(reply.text)
            else -> false
        }

    override fun readProperty(name: String, socket: Path): String? =
        when (val reply = exchange(socket, """{"command":["get_property","$name"]}""")) {
            is IpcReply.Line -> parseMpvData(reply.text)
            else -> null
        }

    private sealed interface IpcReply {
        class Line(val text: String) : IpcReply
        /** mpv closed the connection without a reply line (treated as sent, as before). */
        object Closed : IpcReply
        /** Missing socket, connect/write/read failure, or deadline exceeded. */
        object Failed : IpcReply
    }

    /**
     * One request/response over a fresh non-blocking unix socket, bounded by
     * [IPC_CONNECT_TIMEOUT_MS] for connect and [IPC_IO_TIMEOUT_MS] for write + read.
     * SocketChannel has no SO_TIMEOUT, so readiness is awaited with a [Selector].
     */
    private fun exchange(socket: Path, requestJson: String): IpcReply {
        if (!Files.exists(socket)) return IpcReply.Failed
        return runCatching {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                Selector.open().use { selector ->
                    channel.configureBlocking(false)
                    val key = channel.register(selector, 0)
                    if (!channel.connect(UnixDomainSocketAddress.of(socket))) {
                        val connectDeadline = System.currentTimeMillis() + IPC_CONNECT_TIMEOUT_MS
                        key.interestOps(SelectionKey.OP_CONNECT)
                        while (!channel.finishConnect()) {
                            if (!awaitReady(selector, connectDeadline)) return IpcReply.Failed
                        }
                    }
                    val ioDeadline = System.currentTimeMillis() + IPC_IO_TIMEOUT_MS
                    val payload = ByteBuffer.wrap((requestJson + "\n").toByteArray(Charsets.UTF_8))
                    key.interestOps(SelectionKey.OP_WRITE)
                    while (payload.hasRemaining()) {
                        if (channel.write(payload) == 0 && !awaitReady(selector, ioDeadline)) {
                            return IpcReply.Failed
                        }
                    }
                    key.interestOps(SelectionKey.OP_READ)
                    readIpcReply(channel, selector, ioDeadline)
                }
            }
        }.getOrDefault(IpcReply.Failed)
    }

    /** Waits for the registered interest op until [deadline]; false once the deadline passed. */
    private fun awaitReady(selector: Selector, deadline: Long): Boolean {
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0) return false
        selector.select(remaining)
        selector.selectedKeys().clear()
        return true
    }

    private fun readIpcReply(channel: SocketChannel, selector: Selector, deadline: Long): IpcReply {
        val buffer = ByteBuffer.allocate(4096)
        val line = java.io.ByteArrayOutputStream()
        while (true) {
            val read = channel.read(buffer)
            if (read < 0) {
                val partial = line.toString(Charsets.UTF_8)
                return if (partial.isNotBlank()) IpcReply.Line(partial) else IpcReply.Closed
            }
            if (read == 0) {
                if (!awaitReady(selector, deadline)) return IpcReply.Failed
                continue
            }
            buffer.flip()
            while (buffer.hasRemaining()) {
                val byte = buffer.get()
                if (byte == '\n'.code.toByte()) {
                    val text = line.toString(Charsets.UTF_8)
                    line.reset()
                    // mpv broadcasts events (pause, seek, ...) to every client; skip to our reply.
                    if (!isMpvEventLine(text)) return IpcReply.Line(text)
                } else {
                    line.write(byte.toInt())
                }
            }
            buffer.clear()
        }
    }

    private fun isMpvEventLine(line: String): Boolean =
        line.contains("\"event\"") && !line.contains("\"error\"")

    private const val IPC_CONNECT_TIMEOUT_MS = 1_000L
    private const val IPC_IO_TIMEOUT_MS = 1_500L

    private fun parseMpvData(line: String): String? {
        return runCatching {
            val value = Json.parseToJsonElement(line).jsonObject["data"] ?: return null
            if (value is kotlinx.serialization.json.JsonPrimitive) value.content else value.toString()
        }.getOrNull()
    }

    override fun deleteIpcPath(path: Path) {
        runCatching { Files.deleteIfExists(path) }
    }
}

internal fun isLocalMediaUrl(url: String): Boolean = url.startsWith("file:") || !url.contains("://")

/**
 * What mpv is told to open. mpv refuses the `file:/path` form `File.toURI()` writes (hearted-cache
 * entries store it) though it opens plain paths, so a `file:` URI becomes its path.
 */
internal fun mpvMediaArgument(url: String): String =
    if (url.startsWith("file:")) runCatching { java.nio.file.Paths.get(java.net.URI(url)).toString() }.getOrDefault(url) else url

/**
 * ffmpeg applies ReplayGain only where its demuxer exported it as stream side data (FLAC, MP3,
 * Ogg Vorbis, MP4; not Opus or Matroska), so the tags count only when that side data is present.
 * ffprobe no longer prints the side data's values, so they come from the same tags it was parsed from.
 */
internal fun replayGainFromFfprobe(json: String): ReplayGain? = runCatching {
    val root = Json.parseToJsonElement(json).jsonObject
    val stream = (root["streams"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.jsonObject ?: return null
    val sideData = stream["side_data_list"] as? kotlinx.serialization.json.JsonArray ?: return null
    if (sideData.none { it.jsonObject["side_data_type"]?.jsonPrimitive?.content == "Replay Gain" }) return null
    val tags = buildMap {
        for (source in listOf(root["format"], stream)) {
            val entries = (source as? kotlinx.serialization.json.JsonObject)?.get("tags") as? kotlinx.serialization.json.JsonObject ?: continue
            entries.forEach { (key, value) -> put(key.uppercase(), value.jsonPrimitive.content) }
        }
    }
    VolumeLevelling.parse(tags::get)
}.getOrNull()

internal fun mpvCommandSucceeded(reply: String): Boolean = runCatching {
    kotlinx.serialization.json.Json.parseToJsonElement(reply).jsonObject["error"]?.jsonPrimitive?.content == "success"
}.getOrDefault(false)

internal fun mpvEndedEarly(positionMs: Long, durationMs: Long?): Boolean =
    durationMs != null && durationMs - positionMs > maxOf(5_000L, durationMs / 50)
