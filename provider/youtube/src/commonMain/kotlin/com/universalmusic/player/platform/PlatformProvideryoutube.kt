package com.universalmusic.player.platform

expect fun createYouTubeAudioDownloader(
    streams: YouTubeStreamResolver,
): YouTubeAudioDownloader

expect fun createYouTubeStreamResolver(): YouTubeStreamResolver

/** Android requires a user-selected Connect device to avoid playing on another device. */
