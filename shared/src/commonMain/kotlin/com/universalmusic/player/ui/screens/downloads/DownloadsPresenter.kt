package com.universalmusic.player.ui.screens

import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.data.cache.DownloadsSnapshot
import kotlinx.coroutines.flow.StateFlow

internal class DownloadsPresenter(
    val state: StateFlow<DownloadsSnapshot>,
    private val retryDownload: (String) -> Unit,
    private val removeDownload: (String) -> Unit,
    private val pinDownload: (String, Boolean) -> Unit,
    private val startDownload: (String) -> Unit = {},
    private val wifiOnly: (Boolean) -> Unit = {},
) {
    fun retry(id: String) = retryDownload(id)
    fun remove(id: String) = removeDownload(id)
    fun download(id: String) = startDownload(id)
    fun setWifiOnly(enabled: Boolean) = wifiOnly(enabled)
    fun togglePin(id: String) {
        val item = state.value.items.firstOrNull { it.ownerCanonicalId == id } ?: return
        pinDownload(id, !item.pinned)
    }

    companion object {
        fun from(container: AppContainer): DownloadsPresenter = DownloadsPresenter(
            state = container.heartedAudio.snapshot,
            retryDownload = { container.retryHeartedDownload(it) },
            removeDownload = { container.removeHeartedDownload(it) },
            pinDownload = { id, pinned -> container.setHeartedDownloadPinned(id, pinned) },
            startDownload = { container.downloadHeartedTrack(it) },
            wifiOnly = { container.setDownloadOnWifiOnly(it) },
        )
    }
}
