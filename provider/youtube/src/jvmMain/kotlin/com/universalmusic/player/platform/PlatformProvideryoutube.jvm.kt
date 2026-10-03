package com.universalmusic.player.platform

actual fun createYouTubeAudioDownloader(streams: YouTubeStreamResolver): YouTubeAudioDownloader =
    JvmYouTubeAudioDownloader()

actual fun createYouTubeStreamResolver(): YouTubeStreamResolver = JvmYouTubeStreamResolver()
