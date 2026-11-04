package com.universalmusic.player.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

actual suspend fun saveBackupFile(suggestedName: String, content: String): String? = withContext(Dispatchers.IO) {
    val suggested = Paths.get(System.getProperty("user.home", "."), suggestedName).toString()
    val answer = runDialog(
        "zenity", "--file-selection", "--save", "--confirm-overwrite", "--title=Save Kainos backup",
        "--filename=$suggested", "--file-filter=Kainos backup (*.json) | *.json", parse = ::backupSavePath,
    ) ?: runDialog("kdialog", "--getsavefilename", suggested, "*.json", "--title", "Save Kainos backup", parse = ::backupSavePath)
    val picked = if (answer != null) answer.folder else pickFileWithSwing(save = true, suggested)
    picked?.let { writeBackupFile(Paths.get(it), content) }
}

actual suspend fun openBackupFile(): String? = withContext(Dispatchers.IO) {
    val answer = runDialog(
        "zenity", "--file-selection", "--title=Restore Kainos backup",
        "--file-filter=Kainos backup (*.json) | *.json", "--file-filter=All files | *", parse = ::backupOpenPath,
    ) ?: runDialog("kdialog", "--getopenfilename", System.getProperty("user.home", "."), "*.json", "--title",
        "Restore Kainos backup", parse = ::backupOpenPath)
    val picked = if (answer != null) answer.folder else pickFileWithSwing(save = false, null)
    picked?.let { readBackupFile(Paths.get(it)) }
}

/** A save target in an existing folder; `.json` is added when the name has no extension. */
internal fun backupSavePath(stdout: String): String? {
    val path = lastPickedPath(stdout) ?: return null
    if (path.parent == null || !Files.isDirectory(path.parent) || Files.isDirectory(path)) return null
    val name = path.fileName.toString()
    return (if ('.' in name) path else path.resolveSibling("$name.json")).toString()
}

internal fun backupOpenPath(stdout: String): String? =
    lastPickedPath(stdout)?.takeIf { Files.isRegularFile(it) }?.toString()

/** Writes beside the target, then moves into place, so a failed write never leaves half a backup. */
internal fun writeBackupFile(target: Path, content: String): String {
    val temp = Files.createTempFile(target.parent, ".kainos-backup-", ".tmp")
    try {
        Files.writeString(temp, content)
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (failure: Exception) {
        Files.deleteIfExists(temp)
        throw BackupFileAccessException("Could not save the backup: ${failure.message ?: failure::class.simpleName}")
    }
    return target.fileName.toString()
}

internal fun readBackupFile(path: Path): String {
    val size = runCatching { Files.size(path) }.getOrElse { throw BackupFileAccessException("Could not read that file.") }
    if (size > MAX_BACKUP_FILE_BYTES) throw BackupFileAccessException("That file is too large to be a Kainos backup.")
    return runCatching { Files.readString(path) }.getOrElse { throw BackupFileAccessException("Could not read that file.") }
}

private fun pickFileWithSwing(save: Boolean, suggested: String?): String? {
    fun choose(): String? {
        val frame = javax.swing.JFrame().apply {
            title = "Kainos Player"
            isAlwaysOnTop = true
            setLocationRelativeTo(null)
            isVisible = true
            toFront()
        }
        return try {
            val chooser = javax.swing.JFileChooser().apply {
                dialogTitle = if (save) "Save Kainos backup" else "Restore Kainos backup"
                fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Kainos backup (*.json)", "json")
                suggested?.let { selectedFile = java.io.File(it) }
            }
            val result = if (save) chooser.showSaveDialog(frame) else chooser.showOpenDialog(frame)
            if (result != javax.swing.JFileChooser.APPROVE_OPTION) null
            else chooser.selectedFile?.absolutePath?.let { if (save) backupSavePath(it) else backupOpenPath(it) }
        } finally {
            frame.isVisible = false
            frame.dispose()
        }
    }
    if (javax.swing.SwingUtilities.isEventDispatchThread()) return choose()
    var selected: String? = null
    javax.swing.SwingUtilities.invokeAndWait { selected = choose() }
    return selected
}
