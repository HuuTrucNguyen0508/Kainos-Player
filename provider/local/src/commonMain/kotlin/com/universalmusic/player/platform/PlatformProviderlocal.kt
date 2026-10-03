package com.universalmusic.player.platform

import com.universalmusic.player.data.local.LocalLibraryScanConfig
import com.universalmusic.player.data.local.LocalTrackSource

expect fun createLocalTrackSource(config: () -> LocalLibraryScanConfig): LocalTrackSource

expect fun defaultLocalMusicFolder(): String

expect fun supportsMusicFolderPicker(): Boolean

/** Opens a native directory picker. Returns a path or tree URI, or null if cancelled / unsupported. */

expect suspend fun pickMusicFolder(): String?

/** Best-effort release of a previously granted folder access (Android SAF). No-op on desktop. */

expect fun releaseMusicFolderAccess(folder: String)

/** Wire platform media controls (Android MediaSession / Linux MPRIS) to [session]. */
