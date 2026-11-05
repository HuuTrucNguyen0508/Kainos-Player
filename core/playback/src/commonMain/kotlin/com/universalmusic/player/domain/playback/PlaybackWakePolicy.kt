package com.universalmusic.player.domain.playback

internal fun playbackNeedsWakeLock(spotifyActive: Boolean, status: EngineStatus): Boolean =
    status == EngineStatus.BUFFERING || spotifyActive && status == EngineStatus.PLAYING
