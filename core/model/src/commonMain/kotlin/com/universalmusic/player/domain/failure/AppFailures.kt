package com.universalmusic.player.domain.failure

import com.universalmusic.player.platform.PlaybackTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A later successful write clears its own area. Nothing clears [CORRUPT_ROWS]: the row stays unreadable. */
enum class StorageArea { SETTINGS, LIBRARY, PLAYLISTS, LISTENING, LOCAL_SCAN, CORRUPT_ROWS }

/** Thrown at the storage boundary, so the app scope can tell saved-data failures from every other bug. */
class StorageFailure(val area: StorageArea, cause: Throwable) : Exception(cause.message, cause)

sealed interface AppFailure {
    val message: String

    data class Storage(val area: StorageArea) : AppFailure {
        override val message get() =
            "Some saved data could not be loaded or saved. Restart the app to retry. Your database has been kept."
    }

    /** Reports an event, not a condition: no later success can disprove it, so it leaves on its own. */
    data object Unexpected : AppFailure {
        override val message get() = "Something failed in the background. Details are in the playback log."
    }
}

val Set<AppFailure>.shown: AppFailure?
    get() = firstOrNull { it is AppFailure.Storage } ?: firstOrNull()

object AppFailures {
    private val mutableOpen = MutableStateFlow<Set<AppFailure>>(emptySet())
    val open: StateFlow<Set<AppFailure>> = mutableOpen.asStateFlow()

    val handler = CoroutineExceptionHandler { _, cause -> report(cause) }

    fun report(cause: Throwable) {
        if (cause is CancellationException) return
        PlaybackTrace.failure("Failure", "reported to the user", cause)
        val failure = if (cause is StorageFailure) AppFailure.Storage(cause.area) else AppFailure.Unexpected
        mutableOpen.update { it + failure }
    }

    fun recovered(area: StorageArea) {
        mutableOpen.update { it - AppFailure.Storage(area) }
    }

    fun dismiss() {
        mutableOpen.value = emptySet()
    }

    /** Runs while [failure] is on screen. */
    suspend fun holdOnScreen(failure: AppFailure?) {
        if (failure !is AppFailure.Unexpected) return
        delay(UNEXPECTED_VISIBLE_MS)
        mutableOpen.update { it - AppFailure.Unexpected }
    }

    inline fun reporting(block: () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report(failure)
        }
    }
}

private const val UNEXPECTED_VISIBLE_MS = 12_000L
