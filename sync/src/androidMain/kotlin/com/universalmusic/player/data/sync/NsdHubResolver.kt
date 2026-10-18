package com.universalmusic.player.data.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ext.SdkExtensions
import com.universalmusic.player.platform.androidContext
import com.universalmusic.player.platform.PlaybackTrace
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

internal actual fun platformHubBrowser(): HubBrowser = NsdHubResolver(androidContext)

/** One resolve in flight per process: Android before API 34 fails a second concurrent resolve. */
private val resolveLock = Mutex()
private const val RESOLVE_GUARD_MS = 10_000L

internal class NsdHubResolver(private val context: Context) : HubBrowser {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun browse(): Flow<DiscoveredHub> = callbackFlow {
        val multicastLock = takeMulticastLockIfNeeded()
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                found.trySend(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(IllegalStateException("NSD discovery failed with error $errorCode"))
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        nsd.discoverServices(HOME_LAN_MDNS_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        launch {
            for (info in found) {
                val service = resolve(info) ?: continue
                val txt = service.attributes
                val fp = txt[HOME_LAN_MDNS_TXT_FP]?.decodeToString() ?: continue
                val host = service.addresses().let { all ->
                    all.firstOrNull { it is Inet4Address } ?: all.firstOrNull { !it.isLinkLocalAddress }
                }?.hostAddress ?: continue
                PlaybackTrace.log("HomeSync", "NSD resolved ${service.serviceName} $host:${service.port}")
                send(DiscoveredHub(fp, txt[HOME_LAN_MDNS_TXT_ID]?.decodeToString(), host, service.port, service.serviceName))
            }
        }
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            multicastLock?.release()
        }
    }

    /**
     * Android cannot cancel a resolve before API 34, so the lock is released when Android answers
     * or after [RESOLVE_GUARD_MS], never when a cancelled caller stops waiting.
     */
    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? {
        resolveLock.lock()
        val released = AtomicBoolean(false)
        val release = Runnable { if (released.compareAndSet(false, true)) resolveLock.unlock() }
        val result = CompletableDeferred<NsdServiceInfo?>()
        mainHandler.postDelayed(release, RESOLVE_GUARD_MS)
        val listener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                release.run()
                result.complete(serviceInfo)
            }
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                release.run()
                result.complete(null)
            }
        }
        runCatching { nsd.resolveService(info, listener) }.onFailure {
            release.run()
            return null
        }
        return result.await()
    }

    /** Before T extensions 7, Wi-Fi drops the multicast mDNS answers unless the app holds this lock. */
    private fun takeMulticastLockIfNeeded(): WifiManager.MulticastLock? {
        val systemManaged = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 7
        if (systemManaged) return null
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        return runCatching {
            wifi.createMulticastLock("kainos-hub-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }
}

@Suppress("DEPRECATION")
private fun NsdServiceInfo.addresses(): List<InetAddress> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) hostAddresses else listOfNotNull(host)
