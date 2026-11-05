package com.universalmusic.player.platform

import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FolderPickerAnswerTest {
    @Test fun cancellingEndsThePickInsteadOfOpeningAnotherDialog() {
        assertEquals(FolderPickerAnswer(null), folderPickerAnswer(exitCode = 1, stdout = ""))
    }

    @Test fun aDialogThatFailedLetsTheNextOneTry() {
        assertNull(folderPickerAnswer(exitCode = 127, stdout = ""))
        assertNull(folderPickerAnswer(exitCode = -1, stdout = ""))
    }

    @Test fun thePickedFolderIsReadPastToolChatter() {
        val folder = createTempDirectory("kainos-picker").toRealPath()
        try {
            assertEquals(FolderPickerAnswer(folder.toString()), folderPickerAnswer(0, "Gtk-Message: something\n$folder\n"))
        } finally {
            folder.deleteExisting()
        }
    }
}
