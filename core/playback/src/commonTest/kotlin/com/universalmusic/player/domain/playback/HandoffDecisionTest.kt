package com.universalmusic.player.domain.playback

import com.universalmusic.player.data.library.PersistedSource
import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.session.ListeningState
import com.universalmusic.player.data.session.PeerListening
import com.universalmusic.player.data.session.PeerListeningState
import com.universalmusic.player.data.session.PersistedQueueItem
import com.universalmusic.player.data.session.PortableListeningEntry
import com.universalmusic.player.data.session.PortableListeningQueue
import com.universalmusic.player.data.session.ProjectionFailure
import com.universalmusic.player.data.session.PublicationRevision
import com.universalmusic.player.data.session.QueueProjection
import com.universalmusic.player.data.session.RemoteSession
import com.universalmusic.player.data.session.SessionSnapshot
import com.universalmusic.player.data.session.StoredPeerListening
import com.universalmusic.player.data.sync.LocalSyncIdentityIndex
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals

class HandoffDecisionTest {
    private val idle = LocalHandoffState(isPlaying = false, buffering = false, lastActiveAtMs = NOW - 60_000)

    @Test
    fun aNewerPausedPeerLoadsWithItsCurrentEntry() {
        val expected = PeerHandoff(
            "phone",
            PublicationRevision(9),
            NOW - 1_000,
            ListeningState.create(QUEUE, "q-b", 42_000, NOW - 1_000, false),
        )
        assertEquals(HandoffDecision.Load(expected), decideHandoff(idle, peer(), applied = PublicationRevision(8), NOW))
        assertEquals(HandoffDecision.Load(expected), decideHandoff(idle.copy(lastActiveAtMs = null), peer(), applied = null, NOW))
    }

    @Test
    fun eachSkipReasonHasALiteralState() {
        val cases = listOf(
            HandoffSkipReason.LOCAL_PLAYING to decideHandoff(idle.copy(isPlaying = true), peer(), null, NOW),
            HandoffSkipReason.LOCAL_BUFFERING to decideHandoff(idle.copy(buffering = true), peer(), null, NOW),
            HandoffSkipReason.UNAVAILABLE to decideHandoff(
                idle,
                StoredPeerListening(
                    PeerListening("phone", PublicationRevision(9), NOW - 1_000, PeerListeningState.Unavailable(ProjectionFailure.UNSUPPORTED_TRACK)),
                    queue = null,
                ),
                null,
                NOW,
            ),
            HandoffSkipReason.ALREADY_APPLIED to decideHandoff(idle, peer(), PublicationRevision(9), NOW),
            HandoffSkipReason.TOO_OLD to decideHandoff(
                idle.copy(lastActiveAtMs = null),
                peer(activeAtMs = NOW - HANDOFF_MAX_AGE_MS - 1),
                null,
                NOW,
            ),
            HandoffSkipReason.NOT_NEWER to decideHandoff(idle.copy(lastActiveAtMs = NOW - 1_000), peer(), null, NOW),
            HandoffSkipReason.NO_QUEUE to decideHandoff(idle, peer(queue = null), null, NOW),
            HandoffSkipReason.NOTHING_TO_LOAD to decideHandoff(idle, peer(current = null), null, NOW),
            HandoffSkipReason.INCONSISTENT to decideHandoff(
                idle,
                peer(current = PortableListeningEntry("q-b", QUEUE.entries[0].track)),
                null,
                NOW,
            ),
            HandoffSkipReason.INCONSISTENT to decideHandoff(
                idle,
                peer(current = PortableListeningEntry("q-gone", QUEUE.entries[0].track)),
                null,
                NOW,
            ),
        )
        for ((reason, decision) in cases) assertEquals(HandoffDecision.Skip(reason), decision)
    }

    @Test
    fun aDayOldPeerStillLoads() {
        val decision = decideHandoff(idle.copy(lastActiveAtMs = null), peer(activeAtMs = NOW - HANDOFF_MAX_AGE_MS), null, NOW)
        assertEquals(NOW - HANDOFF_MAX_AGE_MS, (decision as HandoffDecision.Load).peer.lastActiveAtMs)
    }

    @Test
    fun theSnapshotRematchesLocalFilesAndKeepsUnmatchedEntries() {
        val items = listOf(
            PersistedQueueItem("q-song", localFile("song.flac")),
            PersistedQueueItem("q-missing", localFile("missing.flac")),
            PersistedQueueItem("q-yt", PersistedTrack("yt:v1", "Video", durationMs = 200_000, sources = listOf(PersistedSource("YOUTUBE_MUSIC", "v1")))),
        )
        val queue = (PortableListeningQueue.project(items) as QueueProjection.Complete).queue
        val library = Track(
            canonicalId = "local:song",
            title = "Song",
            artists = emptyList(),
            durationMs = 180_000,
            sources = listOf(PlaybackSource(
                provider = ProviderId.LOCAL,
                providerTrackId = "song",
                isPlayable = true,
                handle = PlaybackHandle.Url("/home/user/Music/song.flac"),
            )),
        )
        val handoff = PeerHandoff(
            "phone", PublicationRevision(3), NOW, ListeningState.create(queue, "q-yt", 10_000, NOW - 5_000, isPlaying = true),
        )

        val snapshot = handoff.toSessionSnapshot(LocalSyncIdentityIndex(listOf(library)), NOW)

        assertEquals(
            SessionSnapshot(
                items = listOf(
                    PersistedQueueItem("q-song", PersistedTrack(
                        "local:song", "Song", durationMs = 180_000, cachedAtMs = NOW,
                        sources = listOf(PersistedSource("LOCAL", "song", localLocation = "/home/user/Music/song.flac")),
                    )),
                    items[1],
                    items[2],
                ),
                currentIndex = 2,
                positionMs = 15_000,
                remoteSession = RemoteSession("phone", 3, NOW),
            ),
            snapshot,
        )
    }

    @Test
    fun aPlayingPeerPositionStopsAtTheTrackEnd() {
        val handoff = PeerHandoff("phone", PublicationRevision(3), NOW, ListeningState.create(QUEUE, "q-b", 199_000, NOW - 5_000, true))
        assertEquals(200_000, handoff.toSessionSnapshot(LocalSyncIdentityIndex(emptyList()), NOW).positionMs)
    }

    private fun peer(
        activeAtMs: Long = NOW - 1_000,
        current: PortableListeningEntry? = QUEUE.entries[1],
        queue: PortableListeningQueue? = QUEUE,
    ) = StoredPeerListening(
        PeerListening(
            "phone",
            PublicationRevision(9),
            activeAtMs,
            PeerListeningState.Available(QUEUE.hash, current, 42_000, activeAtMs, isPlaying = false),
        ),
        queue,
    )
}

private const val NOW = 10_000_000_000L

private val QUEUE = (PortableListeningQueue.project(
    listOf("a", "b").map { id ->
        PersistedQueueItem("q-$id", PersistedTrack("yt:$id", "Track $id", durationMs = 200_000, sources = listOf(PersistedSource("YOUTUBE_MUSIC", id))))
    },
) as QueueProjection.Complete).queue

private fun localFile(basename: String) =
    PersistedTrack("localfile:$basename", basename, sources = listOf(PersistedSource("LOCAL", basename)))
