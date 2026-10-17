package com.universalmusic.player.app

import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.data.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AppSettingsUpdater(
    private val store: SettingsStore,
    private val state: MutableStateFlow<AppSettings>,
    private val applyProviderSettings: suspend (AppSettings) -> Unit,
    private val applyPlaybackPreferences: (AppSettings) -> Unit,
) {
    private val mutex = Mutex()

    suspend fun update(transform: (AppSettings) -> AppSettings) = mutex.withLock {
        val previous = state.value
        val transformed = transform(previous)
        val next = if (transformed.spotifyClientId != previous.spotifyClientId) {
            transformed.copy(spotifyPlaybackDeviceId = null, spotifyPlaybackDeviceName = null)
        } else transformed
        store.write(next)
        if (next.spotifyClientId != previous.spotifyClientId || next.youtubeDataApiKey != previous.youtubeDataApiKey) {
            applyProviderSettings(next)
        }
        state.value = next
        applyPlaybackPreferences(next)
    }
}
