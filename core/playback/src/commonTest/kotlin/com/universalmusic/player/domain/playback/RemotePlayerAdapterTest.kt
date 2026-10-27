package com.universalmusic.player.domain.playback

import com.universalmusic.player.data.library.toPersisted
import com.universalmusic.player.data.session.PersistedQueueItem
import com.universalmusic.player.data.sync.RemoteCommand
import com.universalmusic.player.data.sync.RemoteRepeat
import com.universalmusic.player.data.sync.RemoteTransferFailure
import com.universalmusic.player.data.sync.RemoteTransferSnapshot
import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.Artwork
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.RepeatMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class RemotePlayerAdapterTest {
    @Test
    fun transportCommandsApplyAndTheReplyCarriesTheResultingStatus() = runTest {
        val rig = playing("a", "b", "c")

        val paused = rig.adapter.apply(RemoteCommand.TogglePlay)
        assertEquals(false, paused.status?.isPlaying)
        assertFalse(rig.session.nowPlaying.value.isPlaying)

        val resumed = rig.adapter.apply(RemoteCommand.Play)
        runCurrent()
        assertEquals(true, resumed.status?.isPlaying)
        assertTrue(rig.session.nowPlaying.value.isPlaying)

        rig.adapter.apply(RemoteCommand.Pause)
        assertFalse(rig.session.nowPlaying.value.isPlaying)
        rig.adapter.apply(RemoteCommand.Pause)
        assertFalse(rig.session.nowPlaying.value.isPlaying)
    }

    @Test
    fun nextAndPreviousWalkTheQueue() = runTest {
        val rig = playing("a", "b", "c")

        val next = rig.adapter.apply(RemoteCommand.Next)
        runCurrent()
        assertEquals("yt:b", rig.currentId())
        assertEquals("b", next.status?.title)

        rig.adapter.apply(RemoteCommand.Previous)
        runCurrent()
        assertEquals("yt:a", rig.currentId())
    }

    @Test
    fun seekVolumeShuffleAndRepeatApply() = runTest {
        val rig = playing("a", "b", "c")

        assertEquals(42_000, rig.adapter.apply(RemoteCommand.SeekTo(42_000)).status?.positionMs)
        assertEquals(42_000, rig.session.nowPlaying.value.positionMs)

        assertEquals(0.25f, rig.adapter.apply(RemoteCommand.SetVolume(0.25f)).status?.volume)
        assertEquals(0.25f, rig.session.volume.value)

        assertEquals(true, rig.adapter.apply(RemoteCommand.SetShuffle(true)).status?.shuffle)
        assertTrue(rig.session.queueState.value.shuffle)
        rig.adapter.apply(RemoteCommand.SetShuffle(true))
        assertTrue(rig.session.queueState.value.shuffle)

        assertEquals(RemoteRepeat.ONE, rig.adapter.apply(RemoteCommand.SetRepeat(RemoteRepeat.ONE)).status?.repeat)
        assertEquals(RepeatMode.ONE, rig.session.queueState.value.repeat)
    }

    @Test
    fun playItemFindsTheEntryByIdNotIndex() = runTest {
        val rig = playing("a", "b", "c")
        val itemC = rig.session.queueState.value.items[2].id
        rig.session.moveInQueue(2, 0)
        runCurrent()

        rig.adapter.apply(RemoteCommand.PlayItem(itemC))
        runCurrent()

        assertEquals("yt:c", rig.currentId())
    }

    @Test
    fun playNextMovesTheEntryRightAfterTheCurrentOne() = runTest {
        val rig = playing("a", "b", "c", "d")
        val itemD = rig.session.queueState.value.items[3].id

        val reply = rig.adapter.apply(RemoteCommand.PlayNext(itemD))

        assertEquals(listOf("a", "d", "b", "c"), reply.status?.queue?.map { it.title })
        assertEquals("yt:a", rig.currentId())
        rig.adapter.apply(RemoteCommand.Next)
        runCurrent()
        assertEquals("yt:d", rig.currentId())
    }

    @Test
    fun anUnknownItemIdDoesNothing() = runTest {
        val rig = playing("a", "b")
        val before = rig.session.queueState.value

        rig.adapter.apply(RemoteCommand.PlayItem("gone"))
        rig.adapter.apply(RemoteCommand.PlayNext("gone"))
        runCurrent()

        assertEquals(before, rig.session.queueState.value)
        assertEquals("yt:a", rig.currentId())
        assertTrue(rig.session.nowPlaying.value.isPlaying)
    }

    @Test
    fun theStatusListsPlaybackOrderFromTheCurrentEntry() = runTest {
        val rig = playing("a", "b", "c")
        rig.adapter.apply(RemoteCommand.Next)
        runCurrent()

        val status = assertNotNull(rig.adapter.state.value).status
        assertEquals(listOf("b", "c"), status.queue.map { it.title })
        assertEquals(rig.session.queueState.value.current?.id, status.currentItemId)
        assertTrue(status.canSkipNext)
        assertTrue(status.canSkipPrevious)
    }

    @Test
    fun localArtworkGetsAKeyAndHttpArtworkTravelsAsAUrl() = runTest {
        val rig = rig()
        val local = yt("a").copy(artwork = Artwork("file:///music/a/cover.jpg"))
        val web = yt("b").copy(artwork = Artwork("https://img/b.jpg"))
        rig.session.play(listOf(local, web), startIndex = 0)
        runCurrent()

        val first = assertNotNull(rig.adapter.state.value)
        assertNull(first.status.artworkUrl)
        assertNotNull(first.status.artworkKey)
        assertEquals("file:///music/a/cover.jpg", first.localArtwork)

        rig.session.skipToNext()
        runCurrent()
        val second = assertNotNull(rig.adapter.state.value)
        assertEquals("https://img/b.jpg", second.status.artworkUrl)
        assertNull(second.status.artworkKey)
        assertNull(second.localArtwork)
    }

    @Test
    fun pauseForTransferPausesAndCapturesThePortableQueue() = runTest {
        val rig = playing("a", "b", "c")
        rig.adapter.apply(RemoteCommand.Next)
        runCurrent()
        rig.adapter.apply(RemoteCommand.SeekTo(30_000))

        val reply = rig.adapter.apply(RemoteCommand.PauseForTransfer)

        assertFalse(rig.session.nowPlaying.value.isPlaying)
        assertEquals(false, reply.status?.isPlaying)
        val snapshot = assertNotNull(reply.transfer?.snapshot)
        assertNull(reply.transfer?.failure)
        assertEquals(listOf("yt:a", "yt:b", "yt:c"), snapshot.items.map { it.track.canonicalId })
        assertEquals(rig.session.queueState.value.current?.id, snapshot.currentItemId)
        assertEquals(30_000, snapshot.positionMs)
    }

    @Test
    fun anUnportableQueueYieldsATypedFailureAndStaysPaused() = runTest {
        val rig = rig()
        rig.session.play(listOf(yt("a"), track("s", "Artist", provider = ProviderId.SAMPLE, canonicalId = "sample:s")), startIndex = 0)
        runCurrent()

        val reply = rig.adapter.apply(RemoteCommand.PauseForTransfer)

        assertEquals(RemoteTransferFailure.UNSUPPORTED_TRACK, reply.transfer?.failure)
        assertNull(reply.transfer?.snapshot)
        assertFalse(rig.session.nowPlaying.value.isPlaying)
    }

    @Test
    fun anExplicitTransferLoadPlaysEvenWhileThisDeviceIsPlaying() = runTest {
        val rig = playing("local")
        val handoff = ListeningHandoff(FakeExchange(), rig.session, localTracks = { emptyList() }, clock = { testScheduler.currentTime })
        val transfer = RemoteTransferSnapshot(
            items = listOf("x", "y", "z").map { PersistedQueueItem("q-$it", yt(it).toPersisted(0)) },
            currentItemId = "q-y",
            positionMs = 12_000,
            repeat = RemoteRepeat.ALL,
        )

        assertEquals(TransferLoad.PLAYING, handoff.loadTransfer(transfer))
        runCurrent()

        val queue = rig.session.queueState.value
        assertEquals(listOf("q-x", "q-y", "q-z"), queue.items.map { it.id })
        assertEquals("q-y", queue.current?.id)
        assertEquals(RepeatMode.ALL, queue.repeat)
        assertTrue(rig.session.nowPlaying.value.isPlaying)
        assertEquals(12_000, rig.engine.seeks.last())
    }

    @Test
    fun aTransferWhoseCurrentEntryIsMissingIsRejected() = runTest {
        val rig = playing("local")
        val handoff = ListeningHandoff(FakeExchange(), rig.session, localTracks = { emptyList() })
        val transfer = RemoteTransferSnapshot(listOf(PersistedQueueItem("q-x", yt("x").toPersisted(0))), "q-missing", 0)

        assertEquals(TransferLoad.UNSUPPORTED, handoff.loadTransfer(transfer))
        assertEquals("yt:local", rig.currentId())
    }

    private class Rig(val session: PlayerSession, val adapter: RemotePlayerAdapter, val engine: ScriptedEngine) {
        fun currentId() = session.queueState.value.current?.track?.canonicalId
    }

    private fun TestScope.rig(): Rig {
        val engine = ScriptedEngine()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        return Rig(session, RemotePlayerAdapter(session, backgroundScope, clock = { testScheduler.currentTime }), engine)
    }

    private fun TestScope.playing(vararg ids: String): Rig {
        val rig = rig()
        rig.session.play(ids.map(::yt), startIndex = 0)
        runCurrent()
        return rig
    }
}

private fun yt(id: String) = track(id, "Artist", provider = ProviderId.YOUTUBE_MUSIC, canonicalId = "yt:$id")
