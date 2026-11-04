package com.universalmusic.player.ui.screens

import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.data.sync.HeartAction
import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.playback.DefaultSourceResolver
import com.universalmusic.player.domain.playback.EngineState
import com.universalmusic.player.domain.playback.EngineStatus
import com.universalmusic.player.domain.playback.PlaybackEngine
import com.universalmusic.player.domain.playback.PlayerSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class TrackActionsPresenterTest {
    @Test
    fun playNextAndAddToQueueEditTheSessionQueue() = runTest {
        val rig = rig()
        rig.session.play(listOf(track("a"), track("b"), track("c")))
        runCurrent()

        rig.actions.playNext(track("x"))
        assertEquals(listOf("x", "b", "c"), rig.upcoming())
        assertEquals("Playing next: Song x", rig.actions.notice.value?.message)

        rig.actions.addToQueue(track("y"))
        assertEquals(listOf("x", "b", "c", "y"), rig.upcoming())
        assertEquals("Added to queue: Song y", rig.actions.notice.value?.message)
    }

    @Test
    fun queueingIntoAnEmptySessionShowsTheFirstTrackPausedAndPlaysIt() = runTest {
        val rig = rig()
        rig.actions.addToQueue(track("x"))
        rig.actions.playNext(track("y"))
        runCurrent()

        val now = rig.session.nowPlaying.value
        assertEquals("x", now.track?.canonicalId)
        assertFalse(now.isPlaying)
        assertEquals(listOf("y"), rig.upcoming())

        rig.session.togglePlayPause()
        runCurrent()
        assertEquals("x", rig.session.nowPlaying.value.track?.canonicalId)
        assertTrue(rig.session.nowPlaying.value.isPlaying)
    }

    @Test
    fun playNextUnderShuffleIsTheVeryNextTrack() = runTest {
        val rig = rig()
        rig.session.play((1..6).map { track("t$it") })
        rig.session.toggleShuffle()
        runCurrent()

        rig.actions.playNext(track("x"))
        assertEquals("x", rig.upcoming().first())
    }

    @Test
    fun rowsThatCannotPlayInAppStayOutOfTheQueue() = runTest {
        val rig = rig()
        rig.session.play(listOf(track("a")))
        runCurrent()
        val webOnly = track("web", playable = false)

        assertFalse(rig.actions.canQueue(webOnly))
        rig.actions.playNext(webOnly)
        rig.actions.addToQueue(webOnly)
        assertEquals(emptyList(), rig.upcoming())
        assertNull(rig.actions.notice.value)
    }

    @Test
    fun heartingAnotherRowLeavesNowPlayingAlone() = runTest {
        val rig = rig()
        rig.session.play(listOf(track("a"), track("b")))
        runCurrent()

        rig.actions.toggleFavorite(track("b"))

        assertTrue(rig.library.isFavorite("b"))
        assertFalse(rig.session.nowPlaying.value.favorite)
        assertEquals("Hearted Song b", rig.actions.notice.value?.message)
        // Same library path as Now Playing: the heart-download hook and Home sync heart op both follow.
        assertEquals(listOf("b" to true), rig.heartHook)
        assertEquals(HeartAction.FAVORITE, rig.library.toSnapshot().heartOps.single { it.canonicalId == "b" }.action)
    }

    @Test
    fun heartingTheCurrentRowMovesTheNowPlayingHeart() = runTest {
        val rig = rig()
        rig.session.play(listOf(track("a"), track("b")))
        runCurrent()
        assertEquals("a", rig.session.nowPlaying.value.track?.canonicalId)

        rig.actions.toggleFavorite(track("a"))
        assertTrue(rig.session.nowPlaying.value.favorite)

        rig.actions.toggleFavorite(track("a"))
        assertFalse(rig.session.nowPlaying.value.favorite)
        assertFalse(rig.library.isFavorite("a"))
        assertEquals("Removed heart from Song a", rig.actions.notice.value?.message)
        assertEquals(listOf("a" to true, "a" to false), rig.heartHook)
    }

    @Test
    fun addToPlaylistAddsOnceAndNewPlaylistStartsWithTheTrack() = runTest {
        val rig = rig()
        val mix = rig.playlists.create("Mix")

        rig.actions.addToPlaylist(mix.id, track("a"))
        assertEquals(listOf("a"), rig.entries(mix.id))
        assertEquals("Added to Mix", rig.actions.notice.value?.message)

        rig.actions.addToPlaylist(mix.id, track("a"))
        assertEquals(listOf("a"), rig.entries(mix.id))
        assertEquals("Already in Mix", rig.actions.notice.value?.message)

        rig.actions.createPlaylist("  ", track("b"))
        assertEquals(1, rig.playlists.playlists.value.size)

        rig.actions.createPlaylist("  Road trip ", track("b"))
        val road = rig.playlists.playlists.value.single { it.title == "Road trip" }
        assertEquals(listOf("b"), rig.entries(road.id))
        assertEquals("Added to Road trip", rig.actions.notice.value?.message)
    }

    @Test
    fun dismissOnlyClearsTheNoticeItWasTimedFor() = runTest {
        val rig = rig()
        rig.actions.toggleFavorite(track("a"))
        val first = rig.actions.notice.value!!.id
        rig.actions.toggleFavorite(track("b"))

        rig.actions.dismissNotice(first)
        assertEquals("Hearted Song b", rig.actions.notice.value?.message)
        rig.actions.dismissNotice(rig.actions.notice.value!!.id)
        assertNull(rig.actions.notice.value)
    }

    private class Rig(
        val session: PlayerSession,
        val library: LibraryRepository,
        val playlists: KainosPlaylistRepository,
        val heartHook: MutableList<Pair<String, Boolean>>,
    ) {
        val actions = TrackActionsPresenter.from(library, session, playlists)
        fun upcoming() = session.queueState.value.upcoming.map { it.track.canonicalId }
        fun entries(id: String) = playlists.get(id)!!.entries.map { it.track.canonicalId }
    }

    private fun TestScope.rig(): Rig {
        val heartHook = mutableListOf<Pair<String, Boolean>>()
        val library = LibraryRepository(clock = { 1_000 }, onFavoriteChanged = { track, favorite ->
            heartHook += track.canonicalId to favorite
        })
        val session = PlayerSession(
            SilentEngine(), DefaultSourceResolver(), backgroundScope,
            isFavorite = library::isFavorite,
            clock = { testScheduler.currentTime },
        )
        return Rig(session, library, KainosPlaylistRepository(clock = { 1_000 }), heartHook)
    }
}

private fun track(id: String, playable: Boolean = true) = Track(
    canonicalId = id,
    title = "Song $id",
    artists = listOf(ArtistRef("artist", "Artist")),
    sources = listOf(PlaybackSource(ProviderId.LOCAL, id, isPlayable = playable, handle = PlaybackHandle.Url("file:///$id.flac"))),
)

private class SilentEngine : PlaybackEngine {
    override val state = MutableStateFlow(EngineState())
    override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long) {
        state.value = EngineState(EngineStatus.PLAYING, durationMs = 200_000, playGeneration = playGeneration)
    }
    override fun pause() { state.value = state.value.copy(status = EngineStatus.PAUSED) }
    override fun resume() { state.value = state.value.copy(status = EngineStatus.PLAYING) }
    override fun seekTo(positionMs: Long) { state.value = state.value.copy(positionMs = positionMs) }
    override fun stop() { state.value = state.value.copy(status = EngineStatus.IDLE) }
    override fun setVolume(volume: Float) = Unit
}
