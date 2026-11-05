package com.universalmusic.player.domain.echo

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * What the Echo gateway reports about the Echo devices playing local music. Read-only: Alexa lets
 * nothing push commands to an Echo, so the app only shows this.
 */
data class EchoStatus(
    /** Gateway clock when it built the reply. */
    val generatedAtMs: Long,
    /** Local clock when the reply arrived; with [generatedAtMs] it corrects clock skew. */
    val receivedAtMs: Long,
    /** Newest activity first. */
    val echoes: List<EchoDevice>,
) {
    /** The gateway's clock at local time [localNowMs]. */
    fun gatewayNow(localNowMs: Long): Long = localNowMs - (receivedAtMs - generatedAtMs)
}

enum class EchoPlayState { PLAYING, PAUSED, STOPPED, IDLE }

data class EchoTrack(
    val id: String,
    val title: String,
    /** Null when the gateway does not know the artist. */
    val artist: String?,
    val artUrl: String?,
    val durationMs: Long?,
)

data class EchoDevice(
    val device: String,
    val state: EchoPlayState,
    val lastEventAtMs: Long,
    /** Null when idle. */
    val track: EchoTrack?,
    val positionMs: Long,
    val positionAtMs: Long,
    val shuffle: Boolean,
    val loop: Boolean,
    val repeatOne: Boolean,
    val upNext: List<EchoTrack>,
) {
    /** Position at gateway time [gatewayNowMs]: it runs on while playing and holds still otherwise. */
    fun positionAt(gatewayNowMs: Long): Long {
        val running = if (state == EchoPlayState.PLAYING) (gatewayNowMs - positionAtMs).coerceAtLeast(0) else 0
        val position = (positionMs + running).coerceAtLeast(0)
        return track?.durationMs?.takeIf { it > 0 }?.let { position.coerceAtMost(it) } ?: position
    }
}

/** A paused Echo stays on Home this long after its last event. */
const val ECHO_RECENT_WINDOW_MS = 30L * 60 * 1000

/**
 * The Echo to show on Home, or null to show none: an Echo that plays, else the newest one while it
 * is paused within [ECHO_RECENT_WINDOW_MS] of its last event. A stopped Echo will not carry on.
 */
fun EchoStatus.shownEcho(localNowMs: Long): EchoDevice? {
    // The gateway lists the newest event first, so a pause on one Echo can come before another
    // Echo that is still playing.
    echoes.firstOrNull { it.state == EchoPlayState.PLAYING && it.track != null }?.let { return it }
    val newest = echoes.firstOrNull() ?: return null
    if (newest.track == null || newest.state != EchoPlayState.PAUSED) return null
    return newest.takeIf { gatewayNow(localNowMs) - it.lastEventAtMs <= ECHO_RECENT_WINDOW_MS }
}

/** Something that knows what the Echo is playing. Null status means unknown or unreachable. */
interface EchoStatusSource {
    val status: StateFlow<EchoStatus?>
}

const val ECHO_STATUS_VERSION = 1

/** The gateway's placeholder when a file has no artist tag. */
private const val UNKNOWN_ARTIST = "Artiste inconnu"

private val echoJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/**
 * Parses a `/now-playing` reply received at local time [receivedAtMs]. Returns null for anything
 * that is not a version-1 status; an Echo entry that does not parse is skipped on its own.
 */
fun parseEchoStatus(body: String, receivedAtMs: Long): EchoStatus? {
    val wire = try {
        echoJson.decodeFromString(WireStatus.serializer(), body)
    } catch (_: Exception) {
        return null
    }
    if (wire.version != ECHO_STATUS_VERSION) return null
    val echoes = wire.echoes.mapNotNull { element ->
        try {
            echoJson.decodeFromJsonElement(WireEcho.serializer(), element).toDevice()
        } catch (_: Exception) {
            null
        }
    }
    return EchoStatus(wire.generatedAtMs ?: receivedAtMs, receivedAtMs, echoes)
}

@Serializable
private class WireStatus(
    val version: Int? = null,
    @SerialName("generated_at_ms") val generatedAtMs: Long? = null,
    val echoes: List<JsonElement> = emptyList(),
)

@Serializable
private class WireTrack(
    val id: String = "",
    val title: String? = null,
    val artist: String? = null,
    @SerialName("art_url") val artUrl: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
) {
    fun toTrack(): EchoTrack? {
        val name = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return EchoTrack(
            id = id,
            title = name,
            artist = artist?.trim()?.takeIf { it.isNotEmpty() && it != UNKNOWN_ARTIST },
            artUrl = artUrl?.trim()?.takeIf { it.isNotEmpty() },
            durationMs = durationMs?.takeIf { it > 0 },
        )
    }
}

@Serializable
private class WireEcho(
    val device: String = "",
    val state: String? = null,
    @SerialName("last_event_at_ms") val lastEventAtMs: Long = 0,
    val track: WireTrack? = null,
    @SerialName("position_ms") val positionMs: Long = 0,
    @SerialName("position_at_ms") val positionAtMs: Long = 0,
    val shuffle: Boolean = false,
    val loop: Boolean = false,
    @SerialName("repeat_one") val repeatOne: Boolean = false,
    @SerialName("up_next") val upNext: List<WireTrack> = emptyList(),
) {
    fun toDevice(): EchoDevice {
        val track = track?.toTrack()
        val state = when (state?.lowercase()) {
            "playing" -> EchoPlayState.PLAYING
            "paused" -> EchoPlayState.PAUSED
            "stopped" -> EchoPlayState.STOPPED
            else -> EchoPlayState.IDLE
        }
        return EchoDevice(
            device = device,
            state = if (track == null) EchoPlayState.IDLE else state,
            lastEventAtMs = lastEventAtMs,
            track = track,
            positionMs = positionMs.coerceAtLeast(0),
            positionAtMs = positionAtMs,
            shuffle = shuffle,
            loop = loop,
            repeatOne = repeatOne,
            upNext = upNext.mapNotNull { it.toTrack() },
        )
    }
}
