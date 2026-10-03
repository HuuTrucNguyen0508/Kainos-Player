package com.universalmusic.player.ui.screens

import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.platform.PlaybackTraceInfo
import com.universalmusic.player.platform.clearPlaybackTrace
import com.universalmusic.player.platform.playbackTraceInfo
import com.universalmusic.player.platform.sharePlaybackTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class SettingsAdvancedUiState(
    val cacheNotice: String? = null,
    val traceInfo: PlaybackTraceInfo = playbackTraceInfo(),
    val traceNotice: String? = null,
)

internal class SettingsAdvancedPresenter(
    private val clearMetadata: suspend () -> String,
    private val clearAudio: suspend () -> String,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(SettingsAdvancedUiState())
    val state = mutableState.asStateFlow()

    private fun clear(action: suspend () -> String) {
        scope.launch {
            try { mutableState.update { it.copy(cacheNotice = action()) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) {
                mutableState.update { it.copy(cacheNotice = failure.message ?: "Unable to clear cache.") }
            }
        }
    }

    fun clearMetadataCache() = clear(clearMetadata)
    fun clearAudioCache() = clear(clearAudio)
    fun shareLog() {
        val shared = sharePlaybackTrace()
        mutableState.update { it.copy(traceInfo = playbackTraceInfo(),
            traceNotice = if (shared) null else "Nothing to share yet, or no app accepted the file.") }
    }
    fun clearLog() {
        clearPlaybackTrace()
        mutableState.update { it.copy(traceInfo = playbackTraceInfo(), traceNotice = "Playback log cleared.") }
    }

    companion object {
        fun from(container: AppContainer, scope: CoroutineScope) = SettingsAdvancedPresenter(
            clearMetadata = {
                val stats = container.clearMetadataArtworkCache()
                "Cleared metadata/artwork cache (${stats.entryCount} entries, ${stats.artworkFileCount} images)."
            },
            clearAudio = {
                val stats = container.clearHeartedAudioCache()
                "Cleared hearted audio cache (${stats.entryCount} files, ${stats.bytesUsed / (1024 * 1024)} MiB)."
            }, scope = scope,
        )
    }
}
