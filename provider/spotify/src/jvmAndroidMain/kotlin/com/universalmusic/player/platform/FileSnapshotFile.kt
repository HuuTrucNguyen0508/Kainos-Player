package com.universalmusic.player.platform

import com.universalmusic.player.data.spotify.SnapshotFile
import java.io.File

/** Replaced through a temporary file, so a crash mid-write leaves the previous copy. */
internal class FileSnapshotFile(private val file: File) : SnapshotFile {
    override fun read(): String? = file.takeIf { it.isFile }?.readText()

    override fun write(text: String) {
        val dir = checkNotNull(file.parentFile)
        dir.mkdirs()
        val tmp = File(dir, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    override fun delete() {
        file.delete()
    }
}
