package com.universalmusic.player.data.library

import kotlinx.serialization.Serializable

/** Last-writer-wins clock for a pin, an unpin or a reorder: higher [revision] wins, then higher [deviceId]. */
@Serializable
data class HomePinStamp(val revision: Long = 0L, val deviceId: String = "") : Comparable<HomePinStamp> {
    override fun compareTo(other: HomePinStamp): Int =
        compareValuesBy(this, other, HomePinStamp::revision, HomePinStamp::deviceId)
}

/** A portable pin the user removed. It beats any pin of the same target with an older stamp. */
@Serializable
data class HomePinTombstone(val kind: HomePinKind, val targetId: String, val stamp: HomePinStamp)

/**
 * What one device sends on `/kainos-sync/v3/pins`: its portable pins in Home order, its unpins,
 * and the stamp of its last reorder. Spotify playlist pins and unpins appear only under a known
 * [spotifyAccountId], and a receiver applies them only when it is signed into that same account.
 */
@Serializable
data class HomePinsSyncDocument(
    val deviceId: String,
    val spotifyAccountId: String? = null,
    val pins: List<PersistedHomePin> = emptyList(),
    val tombstones: List<HomePinTombstone> = emptyList(),
    val orderStamp: HomePinStamp = HomePinStamp(),
)

/** The pin side of a device's library: all Home pins in order, portable unpins and the last reorder. */
data class HomePinsState(
    val pins: List<PersistedHomePin>,
    val tombstones: List<HomePinTombstone>,
    val orderStamp: HomePinStamp,
)

fun HomePinsState.exportForHomeSync(deviceId: String, spotifyAccountId: String?): HomePinsSyncDocument {
    fun shared(kind: HomePinKind) = kind != HomePinKind.PROVIDER_PLAYLIST || spotifyAccountId != null
    return HomePinsSyncDocument(
        deviceId = deviceId,
        spotifyAccountId = spotifyAccountId,
        pins = pins.portableForHomeSync().filter { shared(it.kind) },
        tombstones = tombstones.filter { shared(it.kind) },
        orderStamp = orderStamp,
    )
}

/** Spotify pins and unpins only for a peer signed into this document's Spotify account. */
fun HomePinsSyncDocument.forPeerSpotifyAccount(peerSpotifyAccountId: String?): HomePinsSyncDocument =
    if (spotifyAccountId != null && peerSpotifyAccountId == spotifyAccountId) this else copy(
        pins = pins.filterNot { it.kind == HomePinKind.PROVIDER_PLAYLIST },
        tombstones = tombstones.filterNot { it.kind == HomePinKind.PROVIDER_PLAYLIST },
    )

/**
 * Merges a peer's portable pins into [local]. Rules:
 * - A pin is identified by kind and target ([pinKey]); its newest pin or unpin wins.
 * - Album and folder pins never take part and keep their places.
 * - Spotify playlist pins take part only when both devices are on the same known Spotify account.
 * - A peer's Kainos playlist pin is added only when [hasTarget] finds that playlist here; until then
 *   it stays off this Home and is offered again on the next sync.
 * - The order of shared pins follows the newer reorder; pins the winner does not list keep the
 *   loser's relative order after it. Shared pins fill the places shared pins held before, and new
 *   ones go at the end. A newer order that lists a pin still waiting for its playlist is followed but
 *   not adopted, so it wins again once that pin can be placed.
 */
fun mergeHomePinsSync(
    local: HomePinsState,
    remote: HomePinsSyncDocument,
    localSpotifyAccountId: String?,
    hasTarget: (PersistedHomePin) -> Boolean,
): HomePinsState {
    val sameSpotifyAccount = localSpotifyAccountId != null && remote.spotifyAccountId == localSpotifyAccountId
    fun takesPart(kind: HomePinKind) =
        kind.canHomeSync && (kind != HomePinKind.PROVIDER_PLAYLIST || sameSpotifyAccount)
    fun PersistedHomePin.takesPart() = forHomeSync() != null && takesPart(kind)
    fun PersistedHomePin.key() = pinKey(kind, targetId)
    fun PersistedHomePin.stamp() = syncStamp ?: HomePinStamp()

    val localLive = local.pins.filter { it.takesPart() }.associateBy { it.key() }
    val localDead = local.tombstones.filter { takesPart(it.kind) }.associateBy { pinKey(it.kind, it.targetId) }
    val remoteLive = remote.pins.mapNotNull { it.forHomeSync() }.migratedHomePins()
        .filter { takesPart(it.kind) }.associateBy { it.key() }
    val remoteDead = remote.tombstones.filter { takesPart(it.kind) }.associateBy { pinKey(it.kind, it.targetId) }

    val usedIds = local.pins.mapTo(HashSet()) { it.id }
    val live = LinkedHashMap<String, PersistedHomePin>()
    val dead = LinkedHashMap<String, HomePinTombstone>()
    var waiting = false
    for (key in localLive.keys + localDead.keys + remoteLive.keys + remoteDead.keys) {
        if (key in live || key in dead) continue
        val mine: HomePinStamp? = listOfNotNull(localLive[key]?.stamp(), localDead[key]?.stamp).maxOrNull()
        val mineIsLive = localLive[key]?.let { it.stamp() == mine } == true
        val theirs: HomePinStamp? = listOfNotNull(remoteLive[key]?.stamp(), remoteDead[key]?.stamp).maxOrNull()
        val theirsIsLive = remoteLive[key]?.let { it.stamp() == theirs } == true
        val remoteWins = theirs != null && (mine == null || theirs > mine)
        when {
            !remoteWins && mineIsLive -> live[key] = localLive.getValue(key)
            !remoteWins -> dead[key] = localDead.getValue(key)
            !theirsIsLive -> dead[key] = remoteDead.getValue(key)
            else -> {
                val pin = remoteLive.getValue(key)
                val existing = localLive[key]
                when {
                    existing != null -> live[key] = pin.copy(id = existing.id)
                    hasTarget(pin) -> live[key] = if (usedIds.add(pin.id)) pin else pin.copy(id = newHomePinId())
                    else -> {
                        waiting = true
                        localDead[key]?.let { dead[key] = it }
                    }
                }
            }
        }
    }

    val localOrder = local.pins.filter { it.takesPart() }.map { it.key() }
    val remoteOrder = remote.pins.map { it.key() }
    val remoteOrderWins = remote.orderStamp > local.orderStamp
    val (first, second) = if (remoteOrderWins) remoteOrder to localOrder else localOrder to remoteOrder
    val ordered = ArrayDeque((first + second + live.keys).distinct().filter { it in live }.map { live.getValue(it) })
    val merged = buildList {
        for (pin in local.pins) {
            if (!pin.takesPart()) add(pin) else ordered.removeFirstOrNull()?.let(::add)
        }
        addAll(ordered)
    }
    return HomePinsState(
        pins = merged.migratedHomePins(),
        tombstones = local.tombstones.filterNot { takesPart(it.kind) } + dead.values,
        orderStamp = if (remoteOrderWins && !waiting) remote.orderStamp else local.orderStamp,
    )
}
