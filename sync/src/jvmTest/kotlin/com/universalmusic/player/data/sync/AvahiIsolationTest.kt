package com.universalmusic.player.data.sync

import com.universalmusic.player.platform.LanAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.types.UInt16
import org.freedesktop.dbus.types.UInt32
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AvahiIsolationTest {
    private val ethernet = LanAddress("eth0", 2, "10.0.0.7")
    private val wifi = LanAddress("wlan0", 3, "192.168.1.57")

    @Test
    fun advertisesEveryEligibleInterfaceAndReconcilesAddressSetChanges() = runBlocking {
        PrivateAvahi().use { bus ->
            val addresses = AtomicReference(setOf(ethernet, wifi))
            val advertiser = AvahiHubAdvertiser(43822, "a".repeat(64), "pc", null,
                bus::client, { addresses.get() }, 30)
            try {
                advertiser.start()
                withTimeout(3_000) { while (bus.commits.size < 1) delay(10) }
                assertEquals(setOf(2, 3), bus.commits[0].toSet(), "both LANs must publish")
                addresses.set(setOf(ethernet, wifi.copy(ipv4 = "192.168.1.58")))
                withTimeout(3_000) { while (bus.commits.size < 2) delay(10) }
                assertEquals(setOf(2, 3), bus.commits[1].toSet())
                addresses.set(emptySet())
                withTimeout(3_000) { while (bus.freed < 2) delay(10) }
            } finally {
                advertiser.stop()
            }
            assertTrue(bus.clients.all { !it.isConnected })
        }
    }

    @Test
    fun browserSetupFailureClosesItsConnection() = runBlocking {
        PrivateAvahi().use { bus ->
            bus.failBrowser = true
            runCatching { AvahiHubBrowser(bus::client).browse().firstOrNull() }
            assertEquals(1, bus.clients.size)
            assertFalse(bus.clients.single().isConnected, "failed setup must release the dedicated connection")
        }
    }

    @Test
    fun normalBrowserEmitsResolvedServiceAndClosesAfterFirstResult() = runBlocking {
        PrivateAvahi().use { bus ->
            bus.announce = true
            val result = withTimeout(3_000) { AvahiHubBrowser(bus::client).browse().firstOrNull() }
            assertEquals(DiscoveredHub("a".repeat(64), "pc", "192.168.1.57", 43822), result)
            assertTrue(bus.clients.all { !it.isConnected })
        }
    }

    @Test
    fun noSignalTimeoutClosesTheBrowser() = runBlocking {
        PrivateAvahi().use { bus ->
            assertNull(withTimeoutOrNull(150) { AvahiHubBrowser(bus::client).browse().firstOrNull() })
            assertTrue(bus.clients.all { !it.isConnected })
        }
    }

    @Test
    fun stalledBrowserCreationCannotOutliveTheResolverDeadline() = stalledRpc("create")

    @Test
    fun stalledResolutionCannotOutliveTheResolverDeadline() = stalledRpc("resolve")

    @Test
    fun stalledFreeCannotOutliveTheResolverDeadline() = stalledRpc("free")

    private fun stalledRpc(stage: String) = runBlocking {
        PrivateAvahi().use { bus ->
            bus.stall = stage
            bus.announce = stage == "resolve"
            val resolver = HubEndpointResolver(AvahiHubBrowser(bus::client), { Result.success(Unit) },
                browseTimeoutMs = 200)
            val started = System.nanoTime()
            val result = resolver.resolve(null, "a".repeat(64))
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            println("PRIVATE_DBUS stage=$stage result=$result elapsedMs=$elapsedMs")
            assertTrue(result is ResolveResult.HubNotFound)
            assertTrue(elapsedMs < 1_000, "200 ms resolver deadline took $elapsedMs ms with stalled $stage")
            assertTrue(bus.clients.all { !it.isConnected }, "no connection may survive cancellation")
        }
    }
}

private class PrivateAvahi : AutoCloseable, TestAvahiServer, AvahiEntryGroup, AvahiServiceBrowser {
    private val process = ProcessBuilder("dbus-daemon", "--session", "--nofork", "--print-address=1")
        .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    private val address = process.inputStream.bufferedReader().readLine()
    private val server = connect()
    val clients = CopyOnWriteArrayList<DBusConnection>()
    val commits = CopyOnWriteArrayList<List<Int>>()
    private val added = mutableListOf<Int>()
    private val release = CountDownLatch(1)
    @Volatile var freed = 0
    @Volatile var announce = false
    @Volatile var failBrowser = false
    @Volatile var stall: String? = null

    init {
        try {
            server.requestBusName(AVAHI_BUS)
            server.exportObject("/", this)
            server.exportObject("/group", this)
            server.exportObject("/browser", this)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun connect() = DBusConnectionBuilder.forAddress(address).withShared(false).build()
    fun client(): DBusConnection = connect().also { clients += it }
    override fun getObjectPath() = "/"
    override fun isRemote() = false
    override fun GetHostName() = "isolated-test"
    override fun GetAlternativeServiceName(name: String) = "$name-2"
    override fun EntryGroupNew() = DBusPath("/group")
    override fun AddService(iface: Int, protocol: Int, flags: UInt32, name: String, type: String,
        domain: String, host: String, port: UInt16, txt: List<ByteArray>) { added += iface }
    override fun Commit() { commits += added.toList(); added.clear() }
    override fun GetState() = 2
    override fun Free() { pause("free"); freed++ }
    override fun ServiceBrowserNew(iface: Int, protocol: Int, type: String, domain: String, flags: UInt32): DBusPath {
        if (failBrowser) throw DBusExecutionException("injected browser creation failure")
        pause("create")
        if (announce) server.sendMessage(AvahiServiceBrowser.ItemNew("/browser", 3, 0,
            "test", HOME_LAN_MDNS_SERVICE_TYPE, "local", UInt32(0)))
        return DBusPath("/browser")
    }
    override fun ResolveService(iface: Int, protocol: Int, name: String, type: String, domain: String,
        aprotocol: Int, flags: UInt32): AvahiWireService<Int, Int, String, String, String, String, Int, String, UInt16, Array<ByteArray>, UInt32> {
        pause("resolve")
        return AvahiWireService(iface, protocol, name, type, domain, "test.local", 0,
            "192.168.1.57", UInt16(43822), listOf("fp=${"a".repeat(64)}", "id=pc")
                .map { it.encodeToByteArray() }.toTypedArray(), UInt32(0))
    }
    private fun pause(stage: String) { if (stall == stage) release.await(2, TimeUnit.SECONDS) }
    override fun close() {
        release.countDown()
        clients.forEach { it.close() }
        server.close()
        process.destroy()
        if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
    }
}

@Suppress("FunctionName")
@DBusInterfaceName("org.freedesktop.Avahi.Server")
internal interface TestAvahiServer : DBusInterface {
    fun GetHostName(): String
    fun GetAlternativeServiceName(name: String): String
    fun EntryGroupNew(): DBusPath
    fun ServiceBrowserNew(iface: Int, protocol: Int, type: String, domain: String, flags: UInt32): DBusPath
    fun ResolveService(iface: Int, protocol: Int, name: String, type: String, domain: String,
        aprotocol: Int, flags: UInt32): AvahiWireService<Int, Int, String, String, String, String, Int, String, UInt16, Array<ByteArray>, UInt32>
}

// dbus-java 5.1.1 requires a parameterized Tuple return type when exporting a method.
internal class AvahiWireService<A, B, C, D, E, F, G, H, I, J, K>(
    @field:Position(0) val iface: A,
    @field:Position(1) val protocol: B,
    @field:Position(2) val name: C,
    @field:Position(3) val type: D,
    @field:Position(4) val domain: E,
    @field:Position(5) val host: F,
    @field:Position(6) val aprotocol: G,
    @field:Position(7) val address: H,
    @field:Position(8) val port: I,
    @field:Position(9) val txt: J,
    @field:Position(10) val flags: K,
) : Tuple()
