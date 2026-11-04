package com.universalmusic.player.platform

import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Set by the activity: launches SAF CreateDocument with the suggested file name. */
var launchBackupSavePicker: ((String) -> Unit)? = null

/** Set by the activity: launches SAF OpenDocument. */
var launchBackupOpenPicker: (() -> Unit)? = null

/** Hands the picked document back from the activity result; one pick at a time. */
object BackupFilePickerRelay {
    @Volatile
    var pending: CompletableDeferred<Uri?>? = null

    fun complete(uri: Uri?) {
        pending?.complete(uri)
        pending = null
    }
}

private suspend fun pickDocument(launch: () -> Unit): Uri? {
    val deferred = CompletableDeferred<Uri?>()
    BackupFilePickerRelay.pending?.cancel()
    BackupFilePickerRelay.pending = deferred
    withContext(Dispatchers.Main) { launch() }
    return deferred.await()
}

actual suspend fun saveBackupFile(suggestedName: String, content: String): String? {
    val launcher = launchBackupSavePicker ?: return null
    val uri = pickDocument { launcher(suggestedName) } ?: return null
    return withContext(Dispatchers.IO) {
        val resolver = androidContext.contentResolver
        runCatching {
            // "wt" truncates, so a shorter backup never keeps the tail of an older one.
            resolver.openOutputStream(uri, "wt")?.use { it.write(content.encodeToByteArray()) }
                ?: error("no output stream")
        }.getOrElse { throw BackupFileAccessException("Could not save the backup there.") }
        displayName(uri) ?: suggestedName
    }
}

actual suspend fun openBackupFile(): String? {
    val launcher = launchBackupOpenPicker ?: return null
    val uri = pickDocument(launcher) ?: return null
    return withContext(Dispatchers.IO) {
        val bytes = try {
            androidContext.contentResolver.openInputStream(uri)?.use(::readCapped) ?: error("no input stream")
        } catch (tooLarge: BackupFileAccessException) {
            throw tooLarge
        } catch (_: Exception) {
            throw BackupFileAccessException("Could not read that file.")
        }
        bytes.decodeToString()
    }
}

private fun readCapped(input: java.io.InputStream): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return out.toByteArray()
        out.write(buffer, 0, read)
        if (out.size() > MAX_BACKUP_FILE_BYTES) throw BackupFileAccessException("That file is too large to be a Kainos backup.")
    }
}

private fun displayName(uri: Uri): String? = runCatching {
    androidContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()
