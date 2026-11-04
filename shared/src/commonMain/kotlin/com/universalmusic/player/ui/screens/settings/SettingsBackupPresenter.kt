package com.universalmusic.player.ui.screens

import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.data.backup.BackupFileException
import com.universalmusic.player.data.backup.BackupRestoreCounts
import com.universalmusic.player.data.backup.KainosBackupFile
import com.universalmusic.player.data.backup.decodeKainosBackup
import com.universalmusic.player.data.backup.encodeKainosBackup
import com.universalmusic.player.platform.BackupFileAccessException
import com.universalmusic.player.platform.currentTimeMillis
import com.universalmusic.player.platform.openBackupFile
import com.universalmusic.player.platform.saveBackupFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface BackupStage {
    data object Idle : BackupStage
    data object Exporting : BackupStage
    data object Reading : BackupStage
    /** A file was read; [counts] say what restoring it would add. Nothing has changed yet. */
    data class Review(val file: KainosBackupFile, val counts: BackupRestoreCounts) : BackupStage
    data object Restoring : BackupStage
    data class Restored(val counts: BackupRestoreCounts) : BackupStage
}

internal data class SettingsBackupUiState(
    val stage: BackupStage = BackupStage.Idle,
    val notice: String? = null,
    val failed: Boolean = false,
) {
    val busy: Boolean get() = stage == BackupStage.Exporting || stage == BackupStage.Reading || stage == BackupStage.Restoring
}

internal class SettingsBackupPresenter(
    private val export: suspend () -> KainosBackupFile,
    private val save: suspend (suggestedName: String, content: String) -> String?,
    private val open: suspend () -> String?,
    private val preview: suspend (KainosBackupFile) -> BackupRestoreCounts,
    private val restore: suspend (KainosBackupFile) -> BackupRestoreCounts,
    private val scope: CoroutineScope,
    private val clock: () -> Long = ::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(SettingsBackupUiState())
    val state = mutableState.asStateFlow()

    fun exportBackup() = run(BackupStage.Exporting) {
        val file = export()
        val name = save(backupFileName(clock()), encodeKainosBackup(file))
        if (name == null) SettingsBackupUiState() else SettingsBackupUiState(notice = "Saved $name: ${exportSummary(file)}.")
    }

    fun chooseFile() = run(BackupStage.Reading) {
        val text = open() ?: return@run SettingsBackupUiState()
        val file = decodeKainosBackup(text)
        SettingsBackupUiState(BackupStage.Review(file, preview(file)))
    }

    fun confirmRestore() {
        val review = state.value.stage as? BackupStage.Review ?: return
        run(BackupStage.Restoring) { SettingsBackupUiState(BackupStage.Restored(restore(review.file))) }
    }

    fun dismiss() {
        if (!state.value.busy) mutableState.value = SettingsBackupUiState()
    }

    private fun run(stage: BackupStage, action: suspend () -> SettingsBackupUiState) {
        if (state.value.busy) return
        mutableState.value = state.value.copy(stage = stage, notice = null, failed = false)
        scope.launch {
            mutableState.value = try {
                action()
            } catch (cancelled: CancellationException) {
                mutableState.value = SettingsBackupUiState()
                throw cancelled
            } catch (failure: BackupFileException) {
                SettingsBackupUiState(notice = failure.message, failed = true)
            } catch (failure: BackupFileAccessException) {
                SettingsBackupUiState(notice = failure.message, failed = true)
            } catch (failure: Exception) {
                SettingsBackupUiState(notice = "Backup failed: ${failure.message ?: failure::class.simpleName}", failed = true)
            }
        }
    }

    companion object {
        private var retained: Pair<AppContainer, SettingsBackupPresenter>? = null

        /**
         * One presenter per container, running on the container's scope: a pick still open when Android
         * recreates the activity, or when the user leaves Settings, finishes and its result is shown on return.
         */
        fun retainedFor(container: AppContainer): SettingsBackupPresenter =
            retained?.takeIf { it.first === container }?.second
                ?: from(container, container.scope).also { retained = container to it }

        private fun from(container: AppContainer, scope: CoroutineScope) = SettingsBackupPresenter(
            export = container.backup::export,
            save = ::saveBackupFile,
            open = ::openBackupFile,
            preview = container.backup::preview,
            restore = container::restoreBackup,
            scope = scope,
        )
    }
}

internal fun exportSummary(file: KainosBackupFile): String = listOf(
    countOf(file.hearts.size, "heart", "hearts"),
    countOf(file.playlists.size, "playlist", "playlists"),
    countOf(file.homePins.size, "Home pin", "Home pins"),
).joinToString(", ")

internal fun countOf(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"

/** `kainos-backup-YYYY-MM-DD.json` for the UTC day of [nowMs]. */
internal fun backupFileName(nowMs: Long): String = "kainos-backup-${utcDate(nowMs)}.json"

/** ISO date of the UTC day containing [epochMs] (civil-from-days, proleptic Gregorian). */
internal fun utcDate(epochMs: Long): String {
    val days = epochMs.floorDiv(86_400_000L)
    val z = days + 719_468
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = yoe + era * 400 + if (month <= 2) 1 else 0
    return "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
}
