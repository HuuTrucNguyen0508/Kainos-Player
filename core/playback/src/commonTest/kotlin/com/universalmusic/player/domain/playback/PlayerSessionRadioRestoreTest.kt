package com.universalmusic.player.domain.playback

import com.universalmusic.player.data.library.toPersisted
import com.universalmusic.player.data.session.ListeningRestore
import com.universalmusic.player.data.session.PersistedQueueItem
import com.universalmusic.player.data.session.PublicationOrigin
import com.universalmusic.player.data.session.SessionSnapshot
import com.universalmusic.player.domain.continuation.QueueContinuation
import com.universalmusic.player.domain.continuation.SourceRadio
import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSessionRadioRestoreTest {
    @Test
    fun aSearchRadioKeepsAppendingAfterARestart() = runTest {
        val store = FakeListeningStore()
        val before = rig(store)
        before.session.initializePersistence()
        before.session.play(listOf(yt("seed")), continuation = SourceRadio(ProviderId.YOUTUBE_MUSIC, fetch = { _, _ -> listOf(yt("mix1")) }))
        settle(before.session)
        assertEquals(listOf("yt:seed", "yt:mix1"), store.restore.snapshot.items.map { it.track.canonicalId })
        assertEquals(ProviderId.YOUTUBE_MUSIC, store.restore.snapshot.radio)

        val requested = mutableListOf<ProviderId>()
        val after = rig(FakeListeningStore(store.restore)) { provider ->
            requested += provider
            SourceRadio(provider, fetch = { _, _ -> listOf(yt("mix2")) })
        }
        after.session.initializePersistence()
        assertEquals(ProviderId.YOUTUBE_MUSIC, after.session.radio.value)
        assertEquals(listOf(ProviderId.YOUTUBE_MUSIC), requested)
        after.session.playTransport()
        runCurrent()
        assertEquals(listOf("yt:seed", "yt:mix1", "yt:mix2"), after.session.queueIds())
    }

    @Test
    fun plainPlaybackRecordsNoRadio() = runTest {
        val store = FakeListeningStore()
        val rig = rig(store)
        rig.session.initializePersistence()
        rig.session.play(listOf(yt("a"), yt("b")))
        settle(rig.session)
        assertEquals(listOf("yt:a", "yt:b"), store.restore.snapshot.items.map { it.track.canonicalId })
        assertEquals(null, store.restore.snapshot.radio)
    }

    @Test
    fun aRadioThatCannotBeRebuiltLetsTheRestoredQueueEnd() = runTest {
        val store = FakeListeningStore(ListeningRestore(saved(radio = ProviderId.SPOTIFY), PublicationOrigin.LOCAL))
        val rig = rig(store) { null }
        rig.session.initializePersistence()
        rig.session.playTransport()
        runCurrent()
        assertEquals(listOf("yt:saved"), rig.session.queueIds())
        settle(rig.session)
        assertEquals(null, store.restore.snapshot.radio)
        assertEquals(null, rig.session.radio.value)
    }

    @Test
    fun aRadioThatRunsOutWhilePausedIsSavedAsFinished() = runTest {
        val store = FakeListeningStore(ListeningRestore(saved(radio = ProviderId.YOUTUBE_MUSIC), PublicationOrigin.LOCAL))
        val radioAnswer = CompletableDeferred<List<Track>>()
        val rig = rig(store) { provider -> SourceRadio(provider, fetch = { _, _ -> radioAnswer.await() }) }
        rig.session.initializePersistence()
        rig.session.playTransport()
        runCurrent()
        rig.session.pauseTransport()
        runCurrent()
        assertEquals(ProviderId.YOUTUBE_MUSIC, store.restore.snapshot.radio)

        radioAnswer.complete(emptyList())
        runCurrent()
        assertEquals(listOf("yt:saved"), store.restore.snapshot.items.map { it.track.canonicalId })
        assertEquals(null, store.restore.snapshot.radio)
    }

    @Test
    fun aFailedRemoteInstallPutsTheRadioBack() = runTest {
        val store = FakeListeningStore(ListeningRestore(saved(radio = ProviderId.YOUTUBE_MUSIC), PublicationOrigin.LOCAL))
        val session = PlayerSession(
            ScriptedEngine(), DefaultSourceResolver(), backgroundScope,
            prepareTrack = { if (it.canonicalId == "yt:broken") error("preparation failed") else it },
            persistence = NowPlayingRecorder(store, backgroundScope), clock = { 0 },
            radioFor = { provider -> SourceRadio(provider, fetch = { _, _ -> listOf(yt("mix")) }) },
        )
        session.initializePersistence()
        val broken = SessionSnapshot(items = listOf(PersistedQueueItem("q-broken", yt("broken").toPersisted(0))))
        val failed = runCatching {
            session.loadRemotePaused(broken, peerHandoffOf(broken, 1), session.localRevision, FakeExchange(store::commit), null)
        }
        assertTrue(failed.isFailure)
        settle(session)
        assertEquals(listOf("yt:saved"), store.restore.snapshot.items.map { it.track.canonicalId })
        assertEquals(ProviderId.YOUTUBE_MUSIC, store.restore.snapshot.radio)
    }

    private class Rig(val session: PlayerSession)

    private fun TestScope.rig(
        store: FakeListeningStore,
        radioFor: (ProviderId) -> QueueContinuation? = { null },
    ): Rig = Rig(
        PlayerSession(
            engine = ScriptedEngine(),
            resolver = DefaultSourceResolver(),
            scope = backgroundScope,
            persistence = NowPlayingRecorder(store, backgroundScope, EmptyCoroutineContext, clock = { 0 }),
            clock = { 0 },
            radioFor = radioFor,
        ),
    )

    private suspend fun TestScope.settle(session: PlayerSession) {
        runCurrent()
        assertIs<FlushResult.Committed>(session.flushPersistence())
    }

    private fun PlayerSession.queueIds() = queueState.value.items.map { it.track.canonicalId }
}

private fun yt(id: String): Track = track(id, "Artist", provider = ProviderId.YOUTUBE_MUSIC, canonicalId = "yt:$id")

private fun saved(radio: ProviderId?) = SessionSnapshot(
    items = listOf(PersistedQueueItem("q-saved", yt("saved").toPersisted(0))),
    radio = radio,
)
