package com.universalmusic.player.data.sync

import com.universalmusic.player.platform.LanAddress
import com.universalmusic.player.platform.PlaybackTrace
import com.universalmusic.player.platform.currentLanAddresses
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt16
import org.freedesktop.dbus.types.UInt32

internal const val AVAHI_BUS = "org.freedesktop.Avahi"
internal const val AVAHI_PROTO_INET = 0
private const val AVAHI_ENTRY_GROUP_REGISTERING = 1
private const val AVAHI_ENTRY_GROUP_ESTABLISHED = 2
private const val AVAHI_ENTRY_GROUP_COLLISION = 3
private const val CHECK_INTERVAL_MS = 30_000L

@Suppress("FunctionName")
@DBusInterfaceName("org.freedesktop.Avahi.Server")
internal interface AvahiServer : DBusInterface {
    fun GetHostName(): String
    fun GetAlternativeServiceName(name: String): String
    fun EntryGroupNew(): DBusPath
    // dbus-java 5.1.1 async lookup uses boxed argument classes.
    fun ServiceBrowserNew(iface: Int?, protocol: Int?, type: String, domain: String, flags: UInt32): DBusPath
    fun ResolveService(
        iface: Int?,
        protocol: Int?,
        name: String,
        type: String,
        domain: String,
        aprotocol: Int?,
        flags: UInt32,
    ): AvahiResolvedService
}

internal class AvahiResolvedService(
    @field:Position(0) val iface: Int,
    @field:Position(1) val protocol: Int,
    @field:Position(2) val name: String,
    @field:Position(3) val type: String,
    @field:Position(4) val domain: String,
    @field:Position(5) val host: String,
    @field:Position(6) val aprotocol: Int,
    @field:Position(7) val address: String,
    @field:Position(8) val port: UInt16,
    /** `aay`; dbus-java fills a reply tuple with boxed bytes, not `byte[]`. */
    @field:Position(9) val txt: List<List<Byte>>,
    @field:Position(10) val flags: UInt32,
) : Tuple()

@Suppress("FunctionName")
@DBusInterfaceName("org.freedesktop.Avahi.EntryGroup")
internal interface AvahiEntryGroup : DBusInterface {
    fun AddService(
        iface: Int,
        protocol: Int,
        flags: UInt32,
        name: String,
        type: String,
        domain: String,
        host: String,
        port: UInt16,
        txt: List<ByteArray>,
    )
    fun Commit()
    fun GetState(): Int
    fun Free()
}

@Suppress("FunctionName")
@DBusInterfaceName("org.freedesktop.Avahi.ServiceBrowser")
internal interface AvahiServiceBrowser : DBusInterface {
    fun Free()

    class ItemNew(
        path: String,
        val iface: Int,
        val protocol: Int,
        val serviceName: String,
        val serviceType: String,
        val domain: String,
        val lookupFlags: UInt32,
    ) : DBusSignal(path, iface, protocol, serviceName, serviceType, domain, lookupFlags)
}

internal fun avahiSystemBus(): DBusConnection = DBusConnectionBuilder.forSystemBus().withShared(false).build()

/** Avahi frees an entry group when the D-Bus connection that created it closes, so a dead process leaves no record. */
class AvahiHubAdvertiser internal constructor(
    private val port: Int,
    private val certSha256Hex: String,
    private val deviceId: String,
    private val instanceLabel: String?,
    private val connectionFactory: () -> DBusConnection,
    private val lanAddresses: () -> Set<LanAddress>,
    private val checkIntervalMs: Long,
) {
    constructor(port: Int, certSha256Hex: String, deviceId: String, instanceLabel: String?) :
        this(port, certSha256Hex, deviceId, instanceLabel, ::avahiSystemBus,
            ::currentLanAddresses, CHECK_INTERVAL_MS)

    private var job: Job? = null
    private var connection: DBusConnection? = null
    private var group: AvahiEntryGroup? = null
    private var publishedOn: Set<LanAddress> = emptySet()
    private var serviceName: String? = null
    private var reportedUnavailable = false

    fun start() {
        if (job != null) return
        job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (isActive) {
                reconcile()
                delay(checkIntervalMs)
            }
        }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        withContext(Dispatchers.IO) { disconnect() }
    }

    private fun reconcile() {
        val targets = lanAddresses()
        val server = runCatching { server() }.getOrElse {
            if (!reportedUnavailable) PlaybackTrace.log(HOME_SYNC_TRACE_TAG, "Avahi unavailable, not advertising")
            reportedUnavailable = true
            disconnect()
            return
        }
        runCatching {
            val state = group?.GetState()
            if (targets == publishedOn && state in setOf(AVAHI_ENTRY_GROUP_REGISTERING, AVAHI_ENTRY_GROUP_ESTABLISHED)) {
                return
            }
            if (state == AVAHI_ENTRY_GROUP_COLLISION) serviceName = serviceName?.let(server::GetAlternativeServiceName)
            withdraw()
            if (targets.isNotEmpty()) publish(server, targets)
        }.onFailure { err ->
            PlaybackTrace.log(HOME_SYNC_TRACE_TAG, "Avahi advertise failed: ${err.message ?: err}")
            disconnect()
        }
    }

    private fun server(): AvahiServer {
        val conn = connection ?: connectionFactory().also { connection = it }
        val server = conn.getRemoteObject(AVAHI_BUS, "/", AvahiServer::class.java)
        val hostName = server.GetHostName()
        if (serviceName == null) serviceName = defaultServiceName(hostName)
        reportedUnavailable = false
        return server
    }

    private fun publish(server: AvahiServer, targets: Set<LanAddress>) {
        val conn = checkNotNull(connection)
        val path = server.EntryGroupNew()
        val entryGroup = conn.getRemoteObject(AVAHI_BUS, path.path, AvahiEntryGroup::class.java)
        group = entryGroup
        val txt = listOf(
            "$HOME_LAN_MDNS_TXT_FP=${certSha256Hex.lowercase()}",
            "$HOME_LAN_MDNS_TXT_ID=$deviceId",
            "$HOME_LAN_MDNS_TXT_VERSION=$HOME_LAN_MDNS_VERSION",
        ).map(String::encodeToByteArray)
        val name = checkNotNull(serviceName)
        for (target in targets.distinctBy { it.interfaceIndex }) {
            entryGroup.AddService(
                target.interfaceIndex, AVAHI_PROTO_INET, UInt32(0), name,
                HOME_LAN_MDNS_SERVICE_TYPE, "", "", UInt16(port), txt,
            )
        }
        entryGroup.Commit()
        publishedOn = targets
        PlaybackTrace.log(HOME_SYNC_TRACE_TAG, "advertising '$name' on ${targets.joinToString()} port $port")
    }

    private fun withdraw() {
        group?.let { runCatching { it.Free() } }
        group = null
        publishedOn = emptySet()
    }

    private fun disconnect() {
        withdraw()
        connection?.let { runCatching { it.close() } }
        connection = null
    }

    private fun defaultServiceName(hostName: String): String =
        "Kainos on $hostName" + instanceLabel?.let { " ($it)" }.orEmpty()
}
