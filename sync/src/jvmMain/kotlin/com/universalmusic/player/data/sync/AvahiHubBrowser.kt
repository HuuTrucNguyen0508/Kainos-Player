package com.universalmusic.player.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.freedesktop.dbus.DBusAsyncReply
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.types.UInt32

internal actual fun platformHubBrowser(): HubBrowser = AvahiHubBrowser()

internal class AvahiHubBrowser(
    private val connectionFactory: () -> DBusConnection = ::avahiSystemBus,
) : HubBrowser {
    override fun browse(): Flow<DiscoveredHub> = flow {
        val connection = connectionFactory()
        val items = Channel<AvahiServiceBrowser.ItemNew>(Channel.UNLIMITED)
        var handler: AutoCloseable? = null
        var browserPath: DBusPath? = null
        try {
            val server = connection.getRemoteObject(AVAHI_BUS, "/", AvahiServer::class.java)
            // Avahi can announce cached services before ServiceBrowserNew returns.
            handler = connection.addSigHandler(AvahiServiceBrowser.ItemNew::class.java) { items.trySend(it) }
            browserPath = connection.callMethodAsync(server, "ServiceBrowserNew",
                -1, AVAHI_PROTO_INET, HOME_LAN_MDNS_SERVICE_TYPE, "", UInt32(0)).awaitReply() as DBusPath
            for (item in items) {
                if (item.path != browserPath.path) continue
                val service = try {
                    connection.callMethodAsync(server, "ResolveService", item.iface, item.protocol,
                        item.serviceName, item.serviceType, item.domain, AVAHI_PROTO_INET, UInt32(0))
                        .awaitReply() as AvahiResolvedService
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    continue
                }
                val txt = parseTxt(service.txt)
                val fp = txt[HOME_LAN_MDNS_TXT_FP] ?: continue
                emit(DiscoveredHub(fp, txt[HOME_LAN_MDNS_TXT_ID], service.address, service.port.toInt()))
            }
        } finally {
            items.close()
            browserPath?.let { path ->
                runCatching {
                    val browser = connection.getRemoteObject(AVAHI_BUS, path.path, AvahiServiceBrowser::class.java)
                    withContext(NonCancellable) {
                        withTimeoutOrNull(100) { connection.callMethodAsync(browser, "Free").awaitCompletion() }
                    }
                }
            }
            // Closing the dedicated connection also frees a browser whose creation reply never arrived.
            runCatching { connection.close() }
            // RemoveMatch is synchronous in 5.1.1. After close it only removes the local handler.
            runCatching { handler?.close() }
        }
    }.flowOn(Dispatchers.IO)
}

private suspend fun DBusAsyncReply<*>.awaitReply(): Any? {
    awaitCompletion()
    return reply
}

private suspend fun DBusAsyncReply<*>.awaitCompletion() {
    while (!call.hasReply()) delay(10)
}

private fun parseTxt(records: List<List<Byte>>): Map<String, String> =
    records.map { it.toByteArray().decodeToString() }
        .filter { '=' in it }
        .associate { it.substringBefore('=') to it.substringAfter('=') }
