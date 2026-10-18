package com.universalmusic.player.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.universalmusic.player.data.db.DbListeningExchangeStore
import com.universalmusic.player.data.db.DbListeningStore
import com.universalmusic.player.data.db.KainosDatabase
import com.universalmusic.player.data.db.KainosStorage
import com.universalmusic.player.data.library.PersistedSource
import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.session.ListeningCommit
import com.universalmusic.player.data.session.ListeningState
import com.universalmusic.player.data.session.LocalListeningUpdate
import com.universalmusic.player.data.session.PersistedQueueItem
import com.universalmusic.player.data.session.PortableListeningQueue
import com.universalmusic.player.data.session.ProjectionFailure
import com.universalmusic.player.data.session.PublicationOrigin
import com.universalmusic.player.data.session.QueueProjection
import com.universalmusic.player.data.session.SessionSnapshot
import com.universalmusic.player.platform.HomeLanHubConfig
import com.universalmusic.player.platform.JvmDeviceIdentity
import java.io.File
import java.util.Properties
import java.util.UUID

/** One device's real database file: [publish] commits like the recorder, [exchange] is what sync reads. */
internal class ListeningDevice(file: File) : AutoCloseable {
    private val driver = JdbcSqliteDriver(
        "jdbc:sqlite:$file",
        Properties().apply { put("foreign_keys", "true") },
        KainosDatabase.Schema,
    )
    private val storage = KainosStorage(openDriver = { driver }, clock = { 0 })
    private val recorder = DbListeningStore(storage)
    private val writer = UUID.randomUUID().toString()
    private var sequence = 0L
    val exchange = DbListeningExchangeStore(storage)

    suspend fun publish(
        items: List<PersistedQueueItem>,
        current: Int,
        activeAtMs: Long,
        positionMs: Long = 0,
        positionAtMs: Long = activeAtMs,
        playing: Boolean = false,
    ) {
        val state = ListeningState.create(portable(items), items[current].id, positionMs, positionAtMs, playing)
        commit(items, current, LocalListeningUpdate.Publish(++sequence, activeAtMs, state))
    }

    suspend fun publishUnavailable(items: List<PersistedQueueItem>, activeAtMs: Long) =
        commit(items, 0, LocalListeningUpdate.Unavailable(++sequence, activeAtMs, ProjectionFailure.UNSUPPORTED_TRACK))

    private suspend fun commit(items: List<PersistedQueueItem>, current: Int, local: LocalListeningUpdate) =
        recorder.commit(
            ListeningCommit(writer, sequence, SessionSnapshot(items = items, currentIndex = current), PublicationOrigin.LOCAL, local, 0),
        )

    override fun close() = driver.close()
}

internal fun items(vararg ids: String) = ids.map { id ->
    PersistedQueueItem("q-$id", PersistedTrack("yt:$id", "Track $id", sources = listOf(PersistedSource("YOUTUBE_MUSIC", id))))
}

internal fun portable(items: List<PersistedQueueItem>): PortableListeningQueue =
    (PortableListeningQueue.project(items) as QueueProjection.Complete).queue

internal fun listeningHub(
    pc: JvmDeviceIdentity.Material,
    port: Int,
    peers: List<PairedPeer>,
    store: DbListeningExchangeStore,
    clock: () -> Long,
) = DesktopHomeLanSyncHub(
    HomeLanHubConfig(
        deviceId = "kainos-pc",
        port = port,
        peers = { peers },
        pairing = PairingHost({ pc.fingerprint }, { "kainos-pc" }, onPaired = {}),
        onHearts = { it },
        onPlaylists = { it },
        vault = null,
        heartedVaultFileNames = { null },
        listening = store,
    ),
    pc,
    bindHost = "127.0.0.1",
    clock = clock,
)
