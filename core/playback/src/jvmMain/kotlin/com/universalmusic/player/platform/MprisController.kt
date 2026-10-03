package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.RepeatMode
import com.universalmusic.player.domain.playback.NowPlayingState
import com.universalmusic.player.domain.playback.PlayerSession
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusException
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant

/**
 * Headless MPRIS media player export for Linux (`playerctl`, desktop shells).
 * Inbound transport calls [PlayerSession]; outbound mirrors nowPlaying + queue.
 */
class MprisController(
    private val session: PlayerSession,
    private val scope: CoroutineScope,
) {
    private val connection = AtomicReference<DBusConnection?>(null)
    private val syncJob = AtomicReference<Job?>(null)
    private val facade = MprisFacade(session) { positionUs ->
        connection.get()?.let { conn ->
            runCatching {
                conn.sendMessage(MprisMediaPlayer2Player.Seeked(OBJECT_PATH, positionUs))
            }
        }
    }

    fun start() {
        if (connection.get() != null) return
        val os = System.getProperty("os.name").orEmpty().lowercase()
        if (!os.contains("linux")) return
        runCatching {
            val conn = DBusConnectionBuilder.forSessionBus()
                .withShared(false)
                .build()
            connection.set(conn)
            conn.requestBusName(BUS_NAME)
            conn.exportObject(OBJECT_PATH, facade)
            syncJob.getAndSet(
                scope.launch {
                    combine(
                        session.nowPlaying,
                        session.queue.queue,
                        session.volume,
                    ) { now, queue, _ -> now to queue }
                        .collectLatest { (now, queue) ->
                            facade.publish(now, queue.shuffle, queue.repeat)
                            emitPropertiesChanged(conn)
                        }
                },
            )?.cancel()
        }.onFailure { error ->
            System.err.println("MprisController failed to start: ${error.message}")
            stop()
        }
    }

    fun stop() {
        syncJob.getAndSet(null)?.cancel()
        connection.getAndSet(null)?.let { conn ->
            runCatching { conn.unExportObject(OBJECT_PATH) }
            runCatching { conn.releaseBusName(BUS_NAME) }
            runCatching { conn.close() }
        }
    }

    private fun emitPropertiesChanged(conn: DBusConnection) {
        runCatching {
            val changed = facade.playerProperties().filterKeys { it != "Position" }
            conn.sendMessage(
                Properties.PropertiesChanged(
                    OBJECT_PATH,
                    IFACE_PLAYER,
                    changed,
                    emptyList(),
                ),
            )
        }
    }

    companion object {
        const val BUS_NAME = "org.mpris.MediaPlayer2.kainosplayer"
        const val OBJECT_PATH = "/org/mpris/MediaPlayer2"
        const val IFACE_ROOT = "org.mpris.MediaPlayer2"
        const val IFACE_PLAYER = "org.mpris.MediaPlayer2.Player"

        fun trackPathForQueueItem(queueItemId: String): String =
            "/kainos/track/${queueItemId.replace(Regex("[^A-Za-z0-9_]"), "_")}"
    }
}

@DBusInterfaceName("org.mpris.MediaPlayer2")
interface MprisMediaPlayer2 : DBusInterface {
    fun Raise()
    fun Quit()
}

@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
interface MprisMediaPlayer2Player : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(offset: Long)
    fun SetPosition(trackId: DBusPath, position: Long)
    fun OpenUri(uri: String)

    class Seeked(path: String, position: Long) : DBusSignal(path, position)
}

@Suppress("UNCHECKED_CAST")
private class MprisFacade(
    private val session: PlayerSession,
    private val emitSeeked: (positionUs: Long) -> Unit,
) : MprisMediaPlayer2, MprisMediaPlayer2Player, Properties {
    @Volatile private var lastShuffle: Boolean = false
    @Volatile private var lastRepeat: RepeatMode = RepeatMode.OFF

    fun publish(@Suppress("UNUSED_PARAMETER") now: NowPlayingState, shuffleEnabled: Boolean, repeat: RepeatMode) {
        lastShuffle = shuffleEnabled
        lastRepeat = repeat
    }

    fun playerProperties(): Map<String, Variant<*>> {
        val now = session.nowPlaying.value
        val hasTrack = now.track != null
        val playbackStatus = when {
            now.isPlaying || now.buffering -> "Playing"
            hasTrack -> "Paused"
            else -> "Stopped"
        }
        val loopStatus = when (lastRepeat) {
            RepeatMode.OFF -> "None"
            RepeatMode.ALL -> "Playlist"
            RepeatMode.ONE -> "Track"
        }
        val position = now.positionMs.coerceAtLeast(0L) * 1_000L
        val canPause = hasTrack && (now.isPlaying || now.buffering)
        return linkedMapOf(
            "PlaybackStatus" to Variant(playbackStatus),
            "LoopStatus" to Variant(loopStatus),
            "Rate" to Variant(java.lang.Double.valueOf(1.0)),
            "Shuffle" to Variant(java.lang.Boolean.valueOf(lastShuffle)),
            "Metadata" to Variant(buildMetadata(now), "a{sv}"),
            "Volume" to Variant(java.lang.Double.valueOf(session.volume.value.toDouble().coerceIn(0.0, 1.0))),
            "Position" to Variant(java.lang.Long.valueOf(position)),
            "MinimumRate" to Variant(java.lang.Double.valueOf(1.0)),
            "MaximumRate" to Variant(java.lang.Double.valueOf(1.0)),
            "CanGoNext" to Variant(java.lang.Boolean.valueOf(session.canSkipNext())),
            "CanGoPrevious" to Variant(java.lang.Boolean.valueOf(hasTrack)),
            "CanPlay" to Variant(java.lang.Boolean.valueOf(hasTrack)),
            "CanPause" to Variant(java.lang.Boolean.valueOf(canPause)),
            "CanSeek" to Variant(java.lang.Boolean.valueOf(hasTrack)),
            "CanControl" to Variant(java.lang.Boolean.TRUE),
        )
    }

    private fun rootProperties(): Map<String, Variant<*>> = linkedMapOf(
        "CanQuit" to Variant(java.lang.Boolean.FALSE),
        "CanRaise" to Variant(java.lang.Boolean.FALSE),
        "HasTrackList" to Variant(java.lang.Boolean.FALSE),
        "Identity" to Variant("Kainos Player"),
        "DesktopEntry" to Variant("kainos-player"),
        "SupportedUriSchemes" to Variant(listOf("file", "http", "https"), "as"),
        "SupportedMimeTypes" to Variant(
            listOf("audio/mpeg", "audio/flac", "audio/ogg", "audio/mp4"),
            "as",
        ),
    )

    private fun buildMetadata(now: NowPlayingState): Map<String, Variant<*>> {
        val track = now.track ?: return emptyMap()
        val queueItemId = now.queueItemId ?: track.canonicalId
        val map = linkedMapOf<String, Variant<*>>()
        map["mpris:trackid"] = Variant(DBusPath(MprisController.trackPathForQueueItem(queueItemId)))
        now.durationMs?.takeIf { it > 0 }?.let {
            map["mpris:length"] = Variant(java.lang.Long.valueOf(it * 1_000L))
        }
        track.artwork?.url?.takeIf { it.isNotBlank() }?.let { map["mpris:artUrl"] = Variant(it) }
        map["xesam:title"] = Variant(track.title)
        map["xesam:artist"] = Variant(
            listOf(track.artistLine.ifBlank { "Unknown artist" }),
            "as",
        )
        track.album?.title?.let { map["xesam:album"] = Variant(it) }
        return map
    }

    override fun Raise() = Unit
    override fun Quit() = Unit

    override fun Next() = session.skipToNext()
    override fun Previous() = session.skipToPrevious()
    override fun Pause() {
        val now = session.nowPlaying.value
        if (now.isPlaying || now.buffering) session.pauseTransport()
    }
    override fun PlayPause() = session.togglePlayPause()
    override fun Stop() = session.clearQueue()
    override fun Play() = session.playTransport()
    override fun Seek(offset: Long) {
        val now = session.nowPlaying.value
        if (now.track == null) return
        val targetMs = (now.positionMs + offset / 1_000L).coerceAtLeast(0L)
        val duration = now.durationMs
        val clamped = if (duration != null) targetMs.coerceAtMost(duration) else targetMs
        session.seekTo(clamped)
        emitSeeked(clamped * 1_000L)
    }
    override fun SetPosition(trackId: DBusPath, position: Long) {
        val now = session.nowPlaying.value
        val queueItemId = now.queueItemId ?: return
        val expected = MprisController.trackPathForQueueItem(queueItemId)
        if (trackId.path != expected) return
        val targetMs = (position / 1_000L).coerceAtLeast(0L)
        val duration = now.durationMs
        if (duration != null && targetMs > duration) return
        session.seekTo(targetMs)
        emitSeeked(targetMs * 1_000L)
    }
    override fun OpenUri(uri: String) = Unit

    override fun <A : Any?> Get(interfaceName: String, propertyName: String): A {
        val props = when (interfaceName) {
            MprisController.IFACE_ROOT -> rootProperties()
            MprisController.IFACE_PLAYER -> playerProperties()
            else -> throw DBusException("Unknown interface $interfaceName")
        }
        val value = props[propertyName] ?: throw DBusException("Unknown property $propertyName")
        return value as A
    }

    override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {
        if (interfaceName != MprisController.IFACE_PLAYER) return
        val raw = (value as? Variant<*>)?.value ?: value
        when (propertyName) {
            "Shuffle" -> {
                val want = raw as? Boolean ?: return
                if (want != session.queue.queue.value.shuffle) session.toggleShuffle()
            }
            "LoopStatus" -> {
                val want = raw as? String ?: return
                val target = when (want) {
                    "None" -> RepeatMode.OFF
                    "Track" -> RepeatMode.ONE
                    "Playlist" -> RepeatMode.ALL
                    else -> return
                }
                var guard = 0
                while (session.queue.queue.value.repeat != target && guard++ < 3) {
                    session.cycleRepeat()
                }
            }
            "Volume" -> {
                val want = when (raw) {
                    is Double -> raw.toFloat()
                    is Float -> raw
                    is Number -> raw.toFloat()
                    else -> return
                }
                session.setVolume(want)
            }
        }
    }

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> = when (interfaceName) {
        MprisController.IFACE_ROOT -> rootProperties()
        MprisController.IFACE_PLAYER -> playerProperties()
        else -> emptyMap()
    }

    override fun isRemote(): Boolean = false
    override fun getObjectPath(): String = MprisController.OBJECT_PATH
}
