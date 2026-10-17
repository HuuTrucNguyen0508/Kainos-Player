package com.universalmusic.player.domain.playback

import com.universalmusic.player.data.session.ListeningCommit
import com.universalmusic.player.data.session.ListeningRestore
import com.universalmusic.player.data.session.ListeningStore
import com.universalmusic.player.data.session.LocalListeningExport
import com.universalmusic.player.data.session.LocalListeningPublication
import com.universalmusic.player.data.session.LocalListeningUpdate
import com.universalmusic.player.data.session.PublicationOrigin
import com.universalmusic.player.data.session.PublicationRevision
import com.universalmusic.player.data.session.SessionSnapshot
import kotlinx.coroutines.CompletableDeferred

internal class FakeListeningStore(
    var restore: ListeningRestore = ListeningRestore(SessionSnapshot(), PublicationOrigin.LOCAL),
) : ListeningStore {
    val commits = mutableListOf<ListeningCommit>()
    val publications = mutableListOf<LocalListeningPublication>()
    var export: LocalListeningExport? = null
        private set
    var attempts = 0
        private set
    var commitGate: CompletableDeferred<Unit>? = null
    var readGate: CompletableDeferred<Unit>? = null
    var failure: Exception? = null
    var failuresLeft = 0
    var readFailure: Exception? = null
    private var revision = 0L
    private var lastActiveAtMs: Long? = null

    override suspend fun readRestore(): ListeningRestore {
        readGate?.await()
        readFailure?.let { throw it }
        return restore
    }

    override suspend fun commit(commit: ListeningCommit) {
        attempts++
        commitGate?.await()
        if (failuresLeft > 0) {
            failuresLeft--
            throw checkNotNull(failure)
        }
        commits += commit
        restore = ListeningRestore(commit.snapshot, commit.origin)
        val local = commit.local ?: return
        revision++
        val active = local.lastActiveAtMs ?: lastActiveAtMs ?: commit.committedAtMs
        lastActiveAtMs = active
        export = when (local) {
            is LocalListeningUpdate.Publish -> LocalListeningExport.Available(
                LocalListeningPublication(PublicationRevision(revision), active, local.state).also(publications::add),
            )
            is LocalListeningUpdate.Unavailable -> LocalListeningExport.Unavailable(PublicationRevision(revision), active, local.reason)
        }
    }

    override suspend fun readLocalExport(): LocalListeningExport? = export

    val latest: LocalListeningPublication get() = publications.last()
}
