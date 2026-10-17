package com.universalmusic.player.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import org.freedesktop.dbus.types.UInt32

internal actual fun platformHubBrowser(): HubBrowser = AvahiHubBrowser()

/** Browses `_kainos-sync._tcp` over IPv4 through avahi-daemon's D-Bus API. */
internal class AvahiHubBrowser : HubBrowser {
    override fun browse(): Flow<DiscoveredHub> = callbackFlow {
        val connection = avahiSystemBus()
        val server = connection.getRemoteObject(AVAHI_BUS, "/", AvahiServer::class.java)
        val items = Channel<AvahiServiceBrowser.ItemNew>(Channel.UNLIMITED)
        // Registered before ServiceBrowserNew, because Avahi announces cached services at once.
        val handler = connection.addSigHandler(AvahiServiceBrowser.ItemNew::class.java) { items.trySend(it) }
        val browserPath = server.ServiceBrowserNew(-1, AVAHI_PROTO_INET, HOME_LAN_MDNS_SERVICE_TYPE, "", UInt32(0))
        launch {
            for (item in items) {
                if (item.path != browserPath.path) continue
                val service = runCatching {
                    server.ResolveService(
                        item.iface, item.protocol, item.serviceName, item.serviceType, item.domain, AVAHI_PROTO_INET, UInt32(0),
                    )
                }.getOrNull() ?: continue
                val txt = parseTxt(service.txt)
                val fp = txt[HOME_LAN_MDNS_TXT_FP] ?: continue
                send(DiscoveredHub(fp, txt[HOME_LAN_MDNS_TXT_ID], service.address, service.port.toInt()))
            }
        }
        awaitClose {
            runCatching {
                connection.getRemoteObject(AVAHI_BUS, browserPath.path, AvahiServiceBrowser::class.java).Free()
            }
            runCatching { handler.close() }
            runCatching { connection.close() }
        }
    }.flowOn(Dispatchers.IO)
}

internal fun parseTxt(records: List<List<Byte>>): Map<String, String> =
    records.map { it.toByteArray().decodeToString() }
        .filter { '=' in it }
        .associate { it.substringBefore('=') to it.substringAfter('=') }
