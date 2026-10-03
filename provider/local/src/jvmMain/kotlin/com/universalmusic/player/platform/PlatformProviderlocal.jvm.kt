package com.universalmusic.player.platform

import com.universalmusic.player.data.local.JvmLocalTrackSource
import com.universalmusic.player.data.local.LocalLibraryRootMode
import com.universalmusic.player.data.local.LocalLibraryScanConfig
import com.universalmusic.player.data.local.LocalTrackSource
import com.universalmusic.player.data.local.resolveMusicRoots
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.readText

actual fun createLocalTrackSource(config: () -> LocalLibraryScanConfig): LocalTrackSource =
    JvmLocalTrackSource {
        val scan = config()
        resolveMusicRoots(
            homeDirectory = Paths.get(System.getProperty("user.home", ".")),
            configuredFolders = scan.folders,
            foldersConfigured = scan.mode == LocalLibraryRootMode.EXPLICIT,
            additionalRoots = System.getenv("KAINOS_MUSIC_DIRS"),
        )
    }

actual fun defaultLocalMusicFolder(): String =
    Paths.get(System.getProperty("user.home", "."), "Music")
        .toAbsolutePath()
        .normalize()
        .toString()

actual fun supportsMusicFolderPicker(): Boolean = true

actual suspend fun pickMusicFolder(): String? {
    pickWithZenity()?.let { return it }
    pickWithKdialog()?.let { return it }
    return pickWithSwing()
}

actual fun releaseMusicFolderAccess(folder: String) = Unit

private fun pickWithZenity(): String? {
    val zenity = findExecutable("zenity") ?: return null
    return runCatching {
        val process = ProcessBuilder(
            zenity,
            "--file-selection",
            "--directory",
            "--title=Choose music folder",
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val stdout = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) null else parsePickedDirectory(stdout)
    }.getOrNull()
}

private fun pickWithKdialog(): String? {
    val kdialog = findExecutable("kdialog") ?: return null
    return runCatching {
        val process = ProcessBuilder(
            kdialog,
            "--getexistingdirectory",
            System.getProperty("user.home", "."),
            "--title",
            "Choose music folder",
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val stdout = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) null else parsePickedDirectory(stdout)
    }.getOrNull()
}

/** Keep the last absolute path-looking line; ignore GTK/tool chatter on stdout. */

private fun parsePickedDirectory(stdout: String): String? {
    val candidate = stdout
        .lineSequence()
        .map(String::trim)
        .filter { it.startsWith('/') }
        .lastOrNull()
        ?: return null
    val path = Paths.get(candidate).toAbsolutePath().normalize()
    return path.takeIf { Files.isDirectory(path) }?.toString()
}

private fun pickWithSwing(): String? {
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
                fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                dialogTitle = "Choose music folder"
                isMultiSelectionEnabled = false
            }
            val result = chooser.showOpenDialog(frame)
            if (result != javax.swing.JFileChooser.APPROVE_OPTION) null
            else chooser.selectedFile?.absoluteFile?.canonicalFile?.path
        } finally {
            frame.isVisible = false
            frame.dispose()
        }
    }
    return if (javax.swing.SwingUtilities.isEventDispatchThread()) {
        choose()
    } else {
        var selected: String? = null
        javax.swing.SwingUtilities.invokeAndWait { selected = choose() }
        selected
    }
}

private fun findExecutable(name: String): String? {
    val path = System.getenv("PATH") ?: return null
    return path.split(':').firstOrNull { dir ->
        val file = java.io.File(dir, name)
        file.canExecute()
    }?.let { java.io.File(it, name).absolutePath }
}

/**
 * Spotify (and other provider) OAuth tokens. Writes are atomic temp+rename with owner-only
 * permissions; read-modify-write is serialized so concurrent writes can't drop entries.
 * A malformed file is quarantined and treated as signed out, so the user just reconnects.
 */
