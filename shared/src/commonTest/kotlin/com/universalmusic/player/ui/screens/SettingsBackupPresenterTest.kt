package com.universalmusic.player.ui.screens

import com.universalmusic.player.data.backup.BackupRestoreCounts
import com.universalmusic.player.data.backup.KainosBackupFile
import com.universalmusic.player.data.backup.decodeKainosBackup
import com.universalmusic.player.data.backup.encodeKainosBackup
import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.platform.BackupFileAccessException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsBackupPresenterTest {
    private val nov4 = 1_793_793_600_000L // 2026-11-04T12:00:00Z
    private val file = KainosBackupFile(createdAtMs = nov4, hearts = listOf(PersistedTrack("yt:a", "A"), PersistedTrack("yt:b", "B")))
    private val counts = BackupRestoreCounts(heartsAdded = 1, heartsAlready = 1)

    private class Calls {
        var savedName: String? = null
        var savedText: String? = null
        val restored = mutableListOf<KainosBackupFile>()
    }

    private fun TestScope.presenter(
        calls: Calls,
        saveAnswer: String? = "picked.json",
        openAnswer: suspend () -> String? = { encodeKainosBackup(file) },
    ) = SettingsBackupPresenter(
        export = { file },
        save = { name, text -> calls.savedName = name; calls.savedText = text; saveAnswer },
        open = openAnswer,
        preview = { counts },
        restore = { calls.restored += it; counts },
        scope = this,
        clock = { nov4 },
    )

    @Test fun exportSavesTheWholeFileUnderADatedNameAndSaysWhatWentIn() = runTest {
        val calls = Calls()
        val presenter = presenter(calls)
        presenter.exportBackup()
        advanceUntilIdle()
        assertEquals("kainos-backup-2026-11-04.json", calls.savedName)
        assertEquals(file, decodeKainosBackup(calls.savedText!!))
        assertEquals(SettingsBackupUiState(notice = "Saved picked.json: 2 hearts, 0 playlists, 0 Home pins."), presenter.state.value)
    }

    @Test fun cancellingEitherPickerLeavesNothingBehind() = runTest {
        val calls = Calls()
        val presenter = presenter(calls, saveAnswer = null, openAnswer = { null })
        presenter.exportBackup()
        advanceUntilIdle()
        assertEquals(SettingsBackupUiState(), presenter.state.value)
        presenter.chooseFile()
        advanceUntilIdle()
        assertEquals(SettingsBackupUiState(), presenter.state.value)
    }

    @Test fun aChosenFileIsReviewedAndOnlyRestoredOnConfirm() = runTest {
        val calls = Calls()
        val presenter = presenter(calls)
        presenter.chooseFile()
        advanceUntilIdle()
        assertEquals(BackupStage.Review(file, counts), presenter.state.value.stage)
        assertEquals(emptyList(), calls.restored)

        presenter.confirmRestore()
        advanceUntilIdle()
        assertEquals(listOf(file), calls.restored)
        assertEquals(BackupStage.Restored(counts), presenter.state.value.stage)
        presenter.dismiss()
        assertEquals(SettingsBackupUiState(), presenter.state.value)
    }

    @Test fun cancelAtReviewRestoresNothing() = runTest {
        val calls = Calls()
        val presenter = presenter(calls)
        presenter.chooseFile()
        advanceUntilIdle()
        presenter.dismiss()
        presenter.confirmRestore()
        advanceUntilIdle()
        assertEquals(emptyList(), calls.restored)
    }

    @Test fun aWrongOrUnreadableFileSaysWhy() = runTest {
        val presenter = presenter(Calls(), openAnswer = { "{\"format\":\"other\"}" })
        presenter.chooseFile()
        advanceUntilIdle()
        assertEquals(SettingsBackupUiState(notice = "This file is not a Kainos backup.", failed = true), presenter.state.value)

        val unreadable = presenter(Calls(), openAnswer = { throw BackupFileAccessException("Could not read that file.") })
        unreadable.chooseFile()
        advanceUntilIdle()
        assertEquals(SettingsBackupUiState(notice = "Could not read that file.", failed = true), unreadable.state.value)
    }

    @Test fun utcDatesAreCivilDates() {
        assertEquals("1970-01-01", utcDate(0))
        assertEquals("2000-02-29", utcDate(951_782_400_000L))
        assertEquals("1969-12-31", utcDate(-1))
        assertEquals("2026-11-04", utcDate(nov4))
        assertTrue(backupFileName(nov4).endsWith(".json"))
    }
}
