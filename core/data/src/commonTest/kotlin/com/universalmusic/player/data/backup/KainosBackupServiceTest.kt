package com.universalmusic.player.data.backup

import com.universalmusic.player.data.library.HomePinKind
import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.library.UserLibrarySnapshot
import com.universalmusic.player.data.library.UserLibraryStore
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.data.playlist.KainosPlaylistStore
import com.universalmusic.player.data.playlist.KainosPlaylistsSnapshot
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.data.settings.FolioTint
import com.universalmusic.player.data.settings.SkinChoice
import com.universalmusic.player.data.settings.ThemeMode
import com.universalmusic.player.data.sync.CertFingerprint
import com.universalmusic.player.data.sync.PairedPeer
import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.Artwork
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KainosBackupServiceTest {
    @Test
    fun anExportCarriesNoSecretsDeviceIdsPathsOrStreamUrls() = runTest {
        val device = device("pc-1", catalog = listOf(songA))
        device.settings = device.settings.copy(
            spotifyClientId = "client-id-7f3a",
            youtubeDataApiKey = "AIzaSy-secret-key",
            spotifyPlaybackDeviceId = "librespot-device-99",
            homeLanSyncDeviceId = "pc-1",
            homeLanSyncHub = PairedPeer("phone-2", "Phone", CertFingerprint.parse("ab".repeat(32))!!, "192.168.1.40", pairedAtMs = 1),
            homeLanSyncPeers = listOf(PairedPeer("phone-3", "Tablet", CertFingerprint.parse("cd".repeat(32))!!, "10.0.0.9", pairedAtMs = 2)),
            homeLanSyncVaultFolder = "/home/alice/Vault",
            localMusicFolders = listOf("/home/alice/Music", "content://com.android.externalstorage.documents/tree/primary%3AMusic"),
        )
        device.fill()

        val json = encodeKainosBackup(device.service.export())

        val forbidden = listOf(
            "client-id-7f3a", "AIzaSy-secret-key", "librespot-device-99", "ab".repeat(32), "cd".repeat(32),
            "192.168.1.40", "10.0.0.9", "phone-2", "phone-3", "/home/alice", "content://", "file://", "googlevideo",
            "\"local:", "local-album:", "accessToken", "refreshToken",
        )
        for (needle in forbidden) assertFalse(needle in json, "backup contains $needle")
        val file = decodeKainosBackup(json)
        assertEquals(
            listOf("localkey:$keyA", "localkey:$keyGone", "spotify:4uLU6hMCjMI75M1A2tKUQC", "yt:dQw4w9WgXcQ"),
            file.hearts.map { it.canonicalId }.sorted(),
        )
        assertEquals(listOf("Morning"), file.playlists.map { it.title })
        assertEquals(
            listOf("localkey:$keyA", "yt:dQw4w9WgXcQ"),
            file.playlists.single().entries.map { it.track.canonicalId },
        )
        assertEquals(listOf(HomePinKind.KAINOS_PLAYLIST, HomePinKind.PROVIDER_PLAYLIST), file.homePins.map { it.kind })
        assertEquals("spotify-user", file.spotifyAccountId)
        assertEquals(ThemeMode.DARK, file.preferences.themeMode)
        assertEquals(FolioTint.NORD, file.preferences.tint)
    }

    @Test
    fun restoringIntoAFreshDeviceAddsEverythingAndASecondRestoreChangesNothing() = runTest {
        val source = device("pc-1", catalog = listOf(songA))
        source.fill()
        val file = decodeKainosBackup(encodeKainosBackup(source.service.export()))
        val fresh = device("phone-2", catalog = listOf(songA.copy(canonicalId = "local:/storage/Music/Song A.flac",
            sources = listOf(localSource("/storage/Music/Song A.flac")))), spotifyAccount = "spotify-user")

        val expected = BackupRestoreCounts(heartsAdded = 4, playlistsAdded = 1, pinsAdded = 2, preferencesChanged = 3)
        assertEquals(expected, fresh.service.preview(file))
        assertEquals(emptySet(), fresh.library.favoriteIds.value)
        assertEquals(expected, fresh.service.restore(file))
        advanceUntilIdle()

        val reopened = fresh.reopen()
        assertEquals(
            setOf("local:/storage/Music/Song A.flac", "localkey:$keyGone", "spotify:4uLU6hMCjMI75M1A2tKUQC", "yt:dQw4w9WgXcQ"),
            reopened.library.favoriteIds.value,
        )
        assertEquals(listOf("Morning"), reopened.playlists.playlists.value.map { it.title })
        assertEquals(
            listOf(HomePinKind.KAINOS_PLAYLIST, HomePinKind.PROVIDER_PLAYLIST),
            reopened.library.homePins.value.map { it.kind },
        )
        assertEquals(ThemeMode.DARK, fresh.settings.themeMode)
        assertEquals(SkinChoice.FOLIO, fresh.settings.skin)
        assertEquals(listOf("yt:dQw4w9WgXcQ", "spotify:4uLU6hMCjMI75M1A2tKUQC", "localkey:$keyGone", "local:/storage/Music/Song A.flac").sorted(),
            fresh.hearted.sorted())

        val libraryBefore = fresh.libraryStore.read()
        val playlistsBefore = fresh.playlistStore.read()
        val settingsBefore = fresh.settings
        val again = BackupRestoreCounts(heartsAlready = 4, playlistsKept = 1, pinsAlready = 2)
        assertEquals(again, fresh.service.preview(file))
        assertEquals(again, fresh.service.restore(file))
        advanceUntilIdle()
        assertEquals(libraryBefore, fresh.libraryStore.read())
        assertEquals(playlistsBefore, fresh.playlistStore.read())
        assertEquals(settingsBefore, fresh.settings)
    }

    @Test
    fun restoringOverExistingDataKeepsEverythingAndOnlyAdds() = runTest {
        val source = device("pc-1", catalog = listOf(songA))
        source.fill()
        val gone = source.playlists.create("Gone", listOf(youtubeSong))
        val file = decodeKainosBackup(encodeKainosBackup(source.service.export()))
        val morningId = file.playlists.first { it.title == "Morning" }.id

        val target = device("phone-2", catalog = listOf(songA), spotifyAccount = "other-user")
        target.clock += 10_000
        target.playlists.applySnapshot(KainosPlaylistsSnapshot(playlists = file.playlists.filter { it.id == morningId }))
        target.playlists.rename(morningId, "Morning, edited here")
        target.playlists.applySnapshot(target.playlists.toSnapshot().copy(playlists = target.playlists.playlists.value +
            file.playlists.first { it.id == gone.id }))
        assertTrue(target.playlists.delete(gone.id))
        val mine = target.playlists.create("Phone only")
        target.library.toggleFavorite(youtubeSong)
        target.library.toggleFavorite(youtubeSong)
        val kept = youtubeSong.copy(canonicalId = "yt:keepme", title = "Kept")
        target.library.toggleFavorite(kept)
        target.library.pinHome(HomePinKind.LOCAL_FOLDER, "/storage/Music", "Music")
        target.library.pinHome(HomePinKind.KAINOS_PLAYLIST, morningId, "Morning")
        target.settings = target.settings.copy(themeMode = ThemeMode.DARK)
        advanceUntilIdle()

        val expected = BackupRestoreCounts(
            heartsAdded = 3, playlistsRestored = 1, playlistsKept = 1, pinsAlready = 1, preferencesChanged = 2, spotifySkipped = 2,
        )
        assertEquals(expected, target.service.preview(file))
        assertEquals(expected, target.service.restore(file))
        advanceUntilIdle()

        val library = target.reopen().library
        assertEquals(
            setOf("local:/home/alice/Music/Song A.flac", "localkey:$keyGone", "yt:dQw4w9WgXcQ", "yt:keepme"),
            library.favoriteIds.value,
        )
        assertEquals(
            listOf("Morning, edited here", "Phone only", "Gone"),
            target.playlists.playlists.value.map { it.title },
        )
        assertEquals(mine.id, target.playlists.playlists.value[1].id)
        assertEquals(listOf(HomePinKind.LOCAL_FOLDER, HomePinKind.KAINOS_PLAYLIST), library.homePins.value.map { it.kind })
        assertEquals(ThemeMode.DARK, target.settings.themeMode)

        val again = BackupRestoreCounts(heartsAlready = 3, playlistsKept = 2, pinsAlready = 1, spotifySkipped = 2)
        assertEquals(again, target.service.restore(file))
    }

    @Test
    fun aNewerLivePlaylistInTheFileReplacesTheOlderOneHere() = runTest {
        val source = device("pc-1")
        val playlist = source.playlists.create("Road trip", listOf(youtubeSong))
        val target = device("phone-2")
        target.playlists.applySnapshot(KainosPlaylistsSnapshot(playlists = listOf(playlist.copy(entries = emptyList(), revision = 1))))
        source.clock += 5
        source.playlists.addTracks(playlist.id, listOf(youtubeSong.copy(canonicalId = "yt:second", sources = listOf(youtubeSource("second")))))

        val counts = target.service.restore(decodeKainosBackup(encodeKainosBackup(source.service.export())))

        assertEquals(BackupRestoreCounts(playlistsUpdated = 1, preferencesChanged = 0), counts)
        assertEquals(listOf("yt:dQw4w9WgXcQ", "yt:second"), target.playlists.get(playlist.id)!!.entries.map { it.track.canonicalId })
    }

    @Test
    fun aFileThatIsNotAKainosBackupIsRefusedWithAMessage() {
        assertEquals("This file is not a Kainos backup.", assertFailsWith<BackupFileException> { decodeKainosBackup("{nope") }.message)
        assertEquals("This file is not a Kainos backup.",
            assertFailsWith<BackupFileException> { decodeKainosBackup("""{"format":"other","version":1}""") }.message)
        assertEquals("This backup comes from a newer Kainos Player. Update the app, then restore it.",
            assertFailsWith<BackupFileException> { decodeKainosBackup("""{"format":"kainos-backup","version":2}""") }.message)
    }

    @Test
    fun aHandEditedFileCannotPlantDeviceIdsOrPaths() = runTest {
        val target = device("phone-2")
        val text = """{"format":"kainos-backup","version":1,"hearts":[
            {"canonicalId":"local:/etc/passwd","title":"x","sources":[{"provider":"LOCAL","providerTrackId":"/etc/passwd","localLocation":"/etc/passwd"}]},
            {"canonicalId":"yt:abc","title":"Y","sources":[{"provider":"YOUTUBE_MUSIC","providerTrackId":"abc","localLocation":"/tmp/x"}]}
        ],"homePins":[{"id":"pin:1","kind":"LOCAL_FOLDER","targetId":"/etc"}]}"""

        val counts = target.service.restore(decodeKainosBackup(text))

        assertEquals(BackupRestoreCounts(heartsAdded = 1), counts)
        assertEquals(setOf("yt:abc"), target.library.favoriteIds.value)
        assertEquals(null, target.library.savedTracks.value.single().sources.single().streamUrl)
        assertEquals(emptyList(), target.library.homePins.value)
    }
}

private val keyA = "lc1:" + "a".repeat(64)
private val keyGone = "lc1:" + "b".repeat(64)

private fun localSource(path: String) = PlaybackSource(ProviderId.LOCAL, path, streamUrl = path, isPlayable = true,
    handle = PlaybackHandle.Url(path))

private fun youtubeSource(id: String) = PlaybackSource(ProviderId.YOUTUBE_MUSIC, id, isPlayable = true,
    handle = PlaybackHandle.Url("https://rr1---sn-abc.googlevideo.com/videoplayback?id=$id"))

private val songA = Track(
    canonicalId = "local:/home/alice/Music/Song A.flac",
    title = "Song A",
    artists = listOf(ArtistRef("local-artist:alice", "Alice")),
    artwork = Artwork("file:///home/alice/.cache/kainos/art.jpg"),
    sources = listOf(localSource("/home/alice/Music/Song A.flac")),
    localContentKey = keyA,
)

private val missingSong = Track(
    canonicalId = "local:/home/alice/Music/Gone.flac",
    title = "Gone",
    artists = listOf(ArtistRef("local-artist:alice", "Alice")),
    sources = listOf(localSource("/home/alice/Music/Gone.flac")),
    localContentKey = keyGone,
)

private val youtubeSong = Track(
    canonicalId = "yt:dQw4w9WgXcQ",
    title = "Never",
    artists = listOf(ArtistRef("yt-artist:rick", "Rick")),
    artwork = Artwork("https://i.ytimg.com/vi/dQw4w9WgXcQ/hq.jpg"),
    sources = listOf(youtubeSource("dQw4w9WgXcQ")),
)

private val spotifySong = Track(
    canonicalId = "spotify:4uLU6hMCjMI75M1A2tKUQC",
    title = "Spot",
    artists = listOf(ArtistRef("spotify:artist:1", "Band")),
    sources = listOf(PlaybackSource(ProviderId.SPOTIFY, "4uLU6hMCjMI75M1A2tKUQC", isPlayable = true,
        handle = PlaybackHandle.ProviderPlayback(ProviderId.SPOTIFY, "4uLU6hMCjMI75M1A2tKUQC", null))),
)

private suspend fun TestScope.device(
    deviceId: String,
    catalog: List<Track> = emptyList(),
    spotifyAccount: String? = "spotify-user",
    libraryStore: MemoryLibraryStore = MemoryLibraryStore(spotifyAccount),
    playlistStore: MemoryPlaylistStore = MemoryPlaylistStore(),
): Device = Device(this, deviceId, catalog, spotifyAccount, libraryStore, playlistStore).also {
    it.library.load(spotifyAccount)
    it.playlists.load()
}

private class Device(
    private val scope: TestScope,
    private val deviceId: String,
    private val catalog: List<Track>,
    private val spotifyAccount: String?,
    val libraryStore: MemoryLibraryStore,
    val playlistStore: MemoryPlaylistStore,
) {
    var clock = 1_000_000L
    var settings = AppSettings()
    val hearted = mutableListOf<String>()
    val library = LibraryRepository(scope = scope, store = libraryStore, clock = { clock }, deviceIdProvider = { deviceId },
        localTrackCatalog = { catalog }, onFavoriteChanged = { track, on -> if (on) hearted += track.canonicalId })
    val playlists = KainosPlaylistRepository(scope = scope, store = playlistStore, clock = { clock },
        deviceIdProvider = { deviceId }, localTrackCatalog = { catalog })
    val service = KainosBackupService(library, playlists, { settings }, { transform -> settings = transform(settings) }, { clock })

    fun fill() {
        library.toggleFavorite(songA)
        library.toggleFavorite(missingSong)
        library.toggleFavorite(youtubeSong)
        library.toggleFavorite(spotifySong)
        val morning = playlists.create("Morning", listOf(songA, youtubeSong))
        library.pinHome(HomePinKind.KAINOS_PLAYLIST, morning.id, "Morning")
        library.pinHome(HomePinKind.PROVIDER_PLAYLIST, "spotify-playlist:37i9dQZF1DX", "Daily", providerEntityId = "37i9dQZF1DX",
            provider = "SPOTIFY")
        library.pinHome(HomePinKind.LOCAL_FOLDER, "/home/alice/Music", "Music")
        library.pinHome(HomePinKind.ALBUM, "local-album:alice|a|1f", "Album A")
        settings = settings.copy(themeMode = ThemeMode.DARK, skin = SkinChoice.FOLIO, tint = FolioTint.NORD)
    }

    suspend fun reopen(): Device = scope.device(deviceId, catalog, spotifyAccount, libraryStore, playlistStore)
}

private class MemoryLibraryStore(spotifyAccount: String?) : UserLibraryStore {
    private var snapshot = UserLibrarySnapshot(spotifyAccountId = spotifyAccount)
    override suspend fun read() = snapshot
    override suspend fun write(snapshot: UserLibrarySnapshot) { this.snapshot = snapshot }
}

private class MemoryPlaylistStore : KainosPlaylistStore {
    private var snapshot = KainosPlaylistsSnapshot()
    override suspend fun read() = snapshot
    override suspend fun write(snapshot: KainosPlaylistsSnapshot) { this.snapshot = snapshot }
}
