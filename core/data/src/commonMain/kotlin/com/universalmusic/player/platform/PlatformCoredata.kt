package com.universalmusic.player.platform

import com.universalmusic.player.data.auth.TokenStore
import com.universalmusic.player.data.cache.MetadataArtworkCache
import com.universalmusic.player.data.config.AppConfig
import com.universalmusic.player.data.library.UserLibraryStore
import com.universalmusic.player.data.playlist.KainosPlaylistStore
import com.universalmusic.player.data.session.SessionSnapshotStore
import com.universalmusic.player.data.settings.SettingsStore
import com.universalmusic.player.data.local.LocalLibraryScanCache
import io.ktor.client.HttpClient

expect fun createHttpClient(): HttpClient

expect fun createTokenStore(): TokenStore

expect fun createSettingsStore(): SettingsStore

expect fun createUserLibraryStore(): UserLibraryStore

expect fun createKainosPlaylistStore(): KainosPlaylistStore

expect fun createSessionSnapshotStore(): SessionSnapshotStore

expect fun createMetadataArtworkCache(): MetadataArtworkCache

expect fun createHeartedAudioCache(): com.universalmusic.player.data.cache.HeartedAudioCache

expect fun createLocalLibraryScanCache(): LocalLibraryScanCache?

expect fun loadAppConfig(): AppConfig

/**
 * Desktop librespot path that can still read Spotify-owned algorithmic playlists
 * (Discover Weekly) after the Nov 2024 Web API restriction. Android returns null for now.
 */
