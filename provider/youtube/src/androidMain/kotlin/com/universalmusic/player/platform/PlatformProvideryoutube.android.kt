package com.universalmusic.player.platform

actual fun createYouTubeAudioDownloader(streams: YouTubeStreamResolver): YouTubeAudioDownloader =
    AndroidYouTubeAudioDownloader(streams)

actual fun createYouTubeStreamResolver(): YouTubeStreamResolver = AndroidYouTubeStreamResolver()
