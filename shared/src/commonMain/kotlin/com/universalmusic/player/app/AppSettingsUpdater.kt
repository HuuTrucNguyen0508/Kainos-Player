package com.universalmusic.player.app

import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AppSettingsUpdater(
    private val scope: CoroutineScope,
    private val store: SettingsStore,
    private val state: MutableStateFlow<AppSettings>,
    private val applyProviderSettings: suspend (AppSettings) -> Unit,
    private val applyPlaybackPreferences: (AppSettings) -> Unit,
) {
    private val mutex = Mutex()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        withContext(scope.coroutineContext.job) {
            mutex.withLock {
                caller.ensureActive()
                val previous = state.value
                val transformed = transform(previous)
                val next = if (transformed.spotifyClientId != previous.spotifyClientId) {
                    transformed.copy(spotifyPlaybackDeviceId = null, spotifyPlaybackDeviceName = null)
                } else transformed
                store.write(next)
                state.value = next
                applyPlaybackPreferences(next)
                if (next.spotifyClientId != previous.spotifyClientId || next.youtubeDataApiKey != previous.youtubeDataApiKey) {
                    applyProviderSettings(next)
                }
            }
        }
        caller.ensureActive()
    }
}
