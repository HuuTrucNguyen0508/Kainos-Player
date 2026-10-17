package com.universalmusic.player.data.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

data class HubEndpoint(val host: String, val port: Int) {
    val authority: String
        get() = if (':' in host && !host.startsWith("[")) "[$host]:$port" else "$host:$port"

    override fun toString(): String = authority
}

data class DiscoveredHub(val fp: String, val id: String?, val host: String, val port: Int) {
    val endpoint: HubEndpoint get() = HubEndpoint(host, port)
}

enum class ResolvedVia { STORED, MDNS }

sealed interface ResolveResult {
    data class Resolved(val endpoint: HubEndpoint, val via: ResolvedVia) : ResolveResult
    data class HubNotFound(val reason: String) : ResolveResult
}

/** Emits [HOME_LAN_MDNS_SERVICE_TYPE] services as they resolve, until the collector stops. */
fun interface HubBrowser {
    fun browse(): Flow<DiscoveredHub>
}

internal expect fun platformHubBrowser(): HubBrowser

class HubEndpointResolver(
    private val browser: HubBrowser,
    private val probe: suspend (HubEndpoint) -> Result<Unit>,
    private val log: (String) -> Unit = {},
    private val probeTimeoutMs: Long = 3_000,
    private val browseTimeoutMs: Long = 4_000,
) {
    suspend fun resolve(stored: HubEndpoint?, pinnedFp: String): ResolveResult {
        val failures = mutableListOf<String>()
        if (stored != null) {
            val failure = probeFailure(stored) ?: return ResolveResult.Resolved(stored, ResolvedVia.STORED)
            failures += "stored $stored: $failure"
        }
        log("discovery browse for $HOME_LAN_MDNS_SERVICE_TYPE")
        val tried = mutableSetOf<HubEndpoint>().apply { stored?.let(::add) }
        var advertised = 0
        val found = withTimeoutOrNull(browseTimeoutMs) {
            browser.browse()
                .catch { log("discovery failed: ${it.message ?: it}") }
                .filter { it.fp.equals(pinnedFp, ignoreCase = true) && tried.add(it.endpoint) }
                .firstOrNull { hub ->
                    advertised++
                    val failure = probeFailure(hub.endpoint)
                    if (failure != null) failures += "mDNS ${hub.endpoint}: $failure"
                    failure == null
                }
        }
        if (found != null) return ResolveResult.Resolved(found.endpoint, ResolvedVia.MDNS)
        val discovery = if (advertised == 0) {
            "mDNS found no hub with the pinned certificate within ${browseTimeoutMs / 1000} s"
        } else {
            "$advertised mDNS hub(s) advertised the pinned certificate but failed the pinned TLS probe"
        }
        return ResolveResult.HubNotFound((failures + discovery).joinToString("; ", prefix = "Hub not found: "))
    }

    private suspend fun probeFailure(endpoint: HubEndpoint): String? {
        val result = withTimeoutOrNull(probeTimeoutMs) { probe(endpoint) }
            ?: return "timed out after ${probeTimeoutMs / 1000} s".also { log("probe $endpoint $it") }
        val error = result.exceptionOrNull() ?: return null
        return (error.message ?: error.toString()).also { log("probe $endpoint failed: $it") }
    }
}
