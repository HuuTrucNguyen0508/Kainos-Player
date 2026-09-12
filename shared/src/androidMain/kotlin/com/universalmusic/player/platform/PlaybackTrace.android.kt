package com.universalmusic.player.platform

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal actual fun writePlaybackTrace(tag: String, message: String) {
    AndroidPlaybackTraceFile.write(tag, message)
}

actual fun playbackTraceInfo(): PlaybackTraceInfo {
    val files = AndroidPlaybackTraceFile.existingFiles()
    return PlaybackTraceInfo(
        location = AndroidPlaybackTraceFile.logFile()?.absolutePath ?: "unavailable",
        sizeBytes = files.sumOf { it.length() },
        canShare = files.isNotEmpty(),
    )
}

actual fun sharePlaybackTrace(): Boolean = AndroidPlaybackTraceFile.share()

actual fun clearPlaybackTrace() = AndroidPlaybackTraceFile.clear()

/**
 * Logcat (tag `KainosTrace`) plus a rotating file so a random pause hours into a listening
 * session can still be inspected afterwards.
 *
 * File: `Android/data/com.universalmusic.player/files/logs/playback-trace.log` on external
 * storage (pull with `adb pull`, or Settings → Share playback log), falling back to the
 * private files dir. Rotates to `playback-trace.1.log` at [MAX_BYTES].
 */
internal object AndroidPlaybackTraceFile {
    private const val LOGCAT_TAG = "KainosTrace"
    private const val FILE_NAME = "playback-trace.log"
    private const val ROTATED_NAME = "playback-trace.1.log"
    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val PROVIDER_SUFFIX = ".playbacktrace"

    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kainos-playback-trace").apply { isDaemon = true }
    }
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var resolvedFile: File? = null

    fun write(tag: String, message: String) {
        val thread = Thread.currentThread().name
        Log.i(LOGCAT_TAG, "[$tag] $message ($thread)")
        val now = Date()
        writer.execute {
            val file = logFile() ?: return@execute
            runCatching {
                rotateIfNeeded(file)
                if (!file.exists() || file.length() == 0L) file.appendText(header())
                file.appendText("${stamp.format(now)} [$tag] $message ($thread)\n")
            }.onFailure { Log.w(LOGCAT_TAG, "trace file write failed: ${it.message}") }
        }
    }

    fun logFile(): File? {
        resolvedFile?.let { return it }
        val context = androidContextOrNull() ?: return null
        val dir = context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")
        return runCatching {
            dir.mkdirs()
            File(dir, FILE_NAME).also { resolvedFile = it }
        }.getOrNull()
    }

    fun existingFiles(): List<File> {
        val current = logFile() ?: return emptyList()
        return listOf(File(current.parentFile, ROTATED_NAME), current).filter { it.isFile && it.length() > 0L }
    }

    /** Hands the current (and rotated, if any) trace to the system share sheet. */
    fun share(): Boolean {
        val context = androidContextOrNull() ?: return false
        flush()
        val files = existingFiles()
        if (files.isEmpty()) return false
        val authority = context.packageName + PROVIDER_SUFFIX
        val uris = runCatching {
            files.map { FileProvider.getUriForFile(context, authority, it) }
        }.getOrElse {
            Log.w(LOGCAT_TAG, "FileProvider uri failed: ${it.message}")
            return false
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
        }
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_SUBJECT, "Kainos Player playback trace")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(intent, "Share playback log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(chooser); true }
            .getOrElse {
                Log.w(LOGCAT_TAG, "share failed: ${it.message}")
                false
            }
    }

    fun clear() {
        flush()
        existingFiles().forEach { runCatching { it.delete() } }
    }

    /** Waits for queued lines so a share never hands off a file missing the last seconds. */
    private fun flush() {
        runCatching { writer.submit {}.get(2, TimeUnit.SECONDS) }
    }

    private fun header(): String {
        val context = androidContextOrNull()
        val version = context?.let {
            runCatching { it.packageManager.getPackageInfo(it.packageName, 0).versionName }.getOrNull()
        } ?: "?"
        return "=== Kainos Player $version | ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) ===\n"
    }

    private fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() < MAX_BYTES) return
        val rotated = File(file.parentFile, ROTATED_NAME)
        rotated.delete()
        file.renameTo(rotated)
    }
}
