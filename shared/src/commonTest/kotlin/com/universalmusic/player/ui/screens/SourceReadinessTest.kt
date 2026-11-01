package com.universalmusic.player.ui.screens

import com.universalmusic.player.app.SourceTools
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.platform.SpotifyReceiverSetup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceReadinessTest {
    @Test fun partlyConnectedLibraryNamesOnlyTheMissingFolder() {
        val local = fresh.copy(
            musicFolders = listOf("/media/A", "/media/B"), localTrackCount = 3,
            localScan = com.universalmusic.player.data.local.LocalScanResult(
                emptyList(), unavailableRoots = listOf("/media/B"), readableRootCount = 1,
            ),
        ).local()
        assertEquals(SourceStatus.Ready, local.status)
        assertEquals("Folder disconnected", local.statusLabel)
        assertTrue("B" in local.detail)
        assertTrue("A" !in local.detail)
        assertEquals(SourceAction.RetryLocal, local.step?.action)
    }

    @Test fun missingDefaultMusicKeepsFirstRunFolderChoice() {
        val local = fresh.copy(
            musicFolders = listOf("/home/a/Music"),
            localScan = com.universalmusic.player.data.local.LocalScanResult(emptyList(), readableRootCount = 0),
        ).local()
        assertEquals(listOf(SourceNeed.MusicFolder), local.needs())
        assertEquals("Choose a music folder", local.step?.label)
        assertEquals(SourceAction.PickMusicFolder, local.step?.action)
    }

    @Test fun disconnectedFolderIsNamedAndOffersRetry() {
        val missing = fresh.copy(local = ProviderState.UNAVAILABLE, musicFolders = listOf("/media/External Music"), localTrackCount = 12).local()
        assertEquals(SourceStatus.Trouble, missing.status)
        assertTrue("External Music" in missing.detail)
        assertEquals("Retry", missing.step?.label)
    }

    private val fresh = SourceFacts(
        desktop = true,
        tools = SourceTools(),
        local = ProviderState.AVAILABLE,
        localTrackCount = 0,
        musicFolders = emptyList(),
        folderPicker = true,
        spotify = ProviderState.NOT_CONFIGURED,
        youtube = ProviderState.NOT_CONFIGURED,
    )

    private fun SourceFacts.local() = sourceReadiness(this)[0]
    private fun SourceFacts.spotify() = sourceReadiness(this)[1]
    private fun SourceFacts.youtube() = sourceReadiness(this)[2]
    private fun SourceReadiness.settingsTarget() = (step?.action as? SourceAction.OpenSettings)?.target
    private fun SourceReadiness.needs() = (status as SourceStatus.NeedsSetup).needs

    @Test fun youtubeWithExtractorStillNeedsTheAudioPlayer() {
        val missing = fresh.copy(youtube = ProviderState.AVAILABLE, tools = SourceTools(audioPlayer = false)).youtube()
        assertEquals("Needs mpv", missing.statusLabel)
        assertEquals("Install mpv", missing.step?.label)
        assertEquals(SourceAction.OpenSettings(SettingsTarget.AudioPlayer), missing.step?.action)
        assertEquals(listOf(SourceNeed.AudioPlayer), missing.needs())
    }

    @Test fun revokedPhoneFolderAccessDoesNotTrustCachedTracks() {
        val missing = fresh.copy(desktop = false, local = ProviderState.UNAVAILABLE, localTrackCount = 12).local()
        assertEquals("Could not read", missing.statusLabel)
        assertEquals("Retry", missing.step?.label)
        assertEquals(SourceAction.RetryLocal, missing.step?.action)
        assertEquals(SourceStatus.Trouble, missing.status)
    }

    @Test fun sourcesLeadWithLocal() {
        assertEquals(listOf(MusicSource.Local, MusicSource.Spotify, MusicSource.YouTube), sourceReadiness(fresh).map { it.source })
    }

    @Test fun noFolderOpensThePickerWhereThereIsOne() {
        val local = fresh.local()
        assertEquals(listOf(SourceNeed.MusicFolder), local.needs())
        assertEquals(SourceAction.PickMusicFolder, local.step?.action)
        assertEquals(SettingsTarget.MusicFolders, fresh.copy(folderPicker = false).local().settingsTarget())
    }

    @Test fun anEmptyDefaultFolderNamesTheFolderThatWasSearched() {
        val local = fresh.copy(musicFolders = listOf("/home/a/Music")).local()
        assertEquals(listOf(SourceNeed.MusicFiles), local.needs())
        assertTrue("Music" in local.detail, local.detail)
    }

    @Test fun scanningAndUnreadableFoldersAreNotSetupGaps() {
        assertEquals(SourceStatus.Checking, fresh.copy(local = ProviderState.LOADING).local().status)
        val failed = fresh.copy(local = ProviderState.UNAVAILABLE).local()
        assertEquals(SourceStatus.Trouble, failed.status)
        assertEquals(SourceAction.RetryLocal, failed.step?.action)
    }

    @Test fun localTracksAreReadyOnlyWithAnAudioPlayer() {
        val found = fresh.copy(localTrackCount = 3, musicFolders = listOf("/m"))
        assertEquals(SourceStatus.Ready, found.local().status)
        assertEquals("3 songs in 1 folder.", found.local().detail)
        val noMpv = found.copy(tools = SourceTools(audioPlayer = false)).local()
        assertEquals(listOf(SourceNeed.AudioPlayer), noMpv.needs())
        assertEquals(SettingsTarget.AudioPlayer, noMpv.settingsTarget())
    }

    @Test fun spotifyWalksClientIdThenSignInThenReceiver() {
        val noKey = fresh.spotify()
        assertEquals(SettingsTarget.SpotifyClientId, noKey.settingsTarget())
        assertTrue("Premium" in noKey.detail)
        assertEquals(SettingsTarget.SpotifySignIn, fresh.copy(spotify = ProviderState.AUTH_REQUIRED).spotify().settingsTarget())

        val signedIn = fresh.copy(spotify = ProviderState.AVAILABLE)
        val notInstalled = signedIn.copy(tools = SourceTools(spotifyReceiver = SpotifyReceiverSetup.NOT_INSTALLED)).spotify()
        assertEquals(listOf(SourceNeed.SpotifyReceiverInstall), notInstalled.needs())
        assertEquals(SettingsTarget.SpotifyPlayback, notInstalled.settingsTarget())
        val needsReceiver = signedIn.copy(tools = SourceTools(spotifyReceiver = SpotifyReceiverSetup.NEEDS_SIGN_IN)).spotify()
        assertEquals(listOf(SourceNeed.SpotifyReceiverSignIn), needsReceiver.needs())
        assertEquals(SourceStatus.Ready, signedIn.spotify().status)
    }

    @Test fun youtubeNeverPromisesPlaybackItCannotDo() {
        assertEquals(SettingsTarget.YouTubeKey, fresh.youtube().settingsTarget())
        val noExtractor = fresh.copy(youtube = ProviderState.AVAILABLE, tools = SourceTools(youtubeExtractor = false)).youtube()
        assertEquals(listOf(SourceNeed.YouTubeExtractor), noExtractor.needs())
        assertEquals(SettingsTarget.YouTubePlayback, noExtractor.settingsTarget())
        val ready = fresh.copy(youtube = ProviderState.AVAILABLE).youtube()
        assertEquals(SourceStatus.Ready, ready.status)
        assertEquals(SourceAction.OpenSearch, ready.step?.action)
    }

    @Test fun limitsAndOutagesPointBackToTheSourceInsteadOfAskingForSetup() {
        for (state in listOf(ProviderState.RATE_LIMITED, ProviderState.UNAVAILABLE)) {
            assertEquals(SourceStatus.Trouble, fresh.copy(spotify = state).spotify().status)
            assertEquals(SourceStatus.Trouble, fresh.copy(youtube = state).youtube().status)
        }
    }

    @Test fun phonesAreNotComputers() {
        assertTrue("this phone" in fresh.copy(desktop = false).local().detail)
        assertTrue("this computer" in fresh.local().detail)
    }
}
