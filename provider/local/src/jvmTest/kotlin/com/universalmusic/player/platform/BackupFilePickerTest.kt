package com.universalmusic.player.platform

import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BackupFilePickerTest {
    private val dir = createTempDirectory("kainos-backup-picker").toRealPath()

    @Test fun theSavePickerAnswerIsReadPastChatterAndGetsAJsonExtension() {
        assertEquals("$dir/mine.json", folderPickerAnswer(0, "Gtk-Message: x\n$dir/mine\n", ::backupSavePath)?.folder)
        assertEquals("$dir/mine.backup", folderPickerAnswer(0, "$dir/mine.backup\n", ::backupSavePath)?.folder)
    }

    @Test fun aSaveTargetInAMissingFolderOrOnAFolderIsRefused() {
        assertNull(backupSavePath("$dir/missing/mine.json"))
        assertNull(backupSavePath(dir.resolve("sub").createDirectory().toString()))
    }

    @Test fun cancellingTheSavePickerEndsThePick() {
        assertEquals(FolderPickerAnswer(null), folderPickerAnswer(1, "", ::backupSavePath))
    }

    @Test fun theOpenPickerOnlyAcceptsAnExistingFile() {
        val file = dir.resolve("b.json").also { it.writeText("{}") }
        assertEquals(file.toString(), backupOpenPath("$file\n"))
        assertNull(backupOpenPath("$dir/none.json"))
        assertNull(backupOpenPath(dir.toString()))
    }

    @Test fun savingReplacesTheFileWholeAndLeavesNoTempFileBehind() {
        val target = dir.resolve("kainos-backup.json").also { it.writeText("old and longer content") }
        assertEquals("kainos-backup.json", writeBackupFile(target, "{\"v\":1}"))
        assertEquals("{\"v\":1}", target.readText())
        assertEquals(listOf(target), dir.listDirectoryEntries())
        assertEquals("{\"v\":1}", readBackupFile(target))
    }

    @Test fun anOversizedFileIsRefusedUnread() {
        val big = dir.resolve("big.json")
        java.io.RandomAccessFile(big.toFile(), "rw").use { it.setLength(MAX_BACKUP_FILE_BYTES + 1L) }
        assertEquals("That file is too large to be a Kainos backup.",
            assertFailsWith<BackupFileAccessException> { readBackupFile(big) }.message)
    }
}
