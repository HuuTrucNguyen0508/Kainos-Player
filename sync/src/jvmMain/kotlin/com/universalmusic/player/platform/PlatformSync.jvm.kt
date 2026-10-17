package com.universalmusic.player.platform

import com.universalmusic.player.data.sync.HubOwnership
import com.universalmusic.player.data.sync.HubProcessMode
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

private val hubUserService by lazy { HubUserService.current() }

private var processHubOwnership: HubOwnership? = null

fun desktopHubOwnership(hubOnly: Boolean): HubOwnership {
    val mode = hubUserService.processMode(hubOnly, System.getenv("KAINOS_HUB_UNIT"))
    check(processHubOwnership == null)
    return HubOwnership(DesktopIsolation.current, hubUserService, mode).also { processHubOwnership = it }
}

actual fun createHomeLanSyncHub(config: HomeLanHubConfig): com.universalmusic.player.data.sync.HomeLanSyncHub {
    val identity = jvmPlatformDeviceIdentity()
    val hub = com.universalmusic.player.data.sync.DesktopHomeLanSyncHub(config, identity.material())
    val advertiser = com.universalmusic.player.data.sync.AvahiHubAdvertiser(
        port = config.port,
        certSha256Hex = identity.fingerprint().hex,
        deviceId = config.deviceId,
        instanceLabel = DesktopIsolation.current.instance,
    )
    return AdvertisedHub(hub, advertiser)
}

private class AdvertisedHub(
    private val hub: com.universalmusic.player.data.sync.HomeLanSyncHub,
    private val advertiser: com.universalmusic.player.data.sync.AvahiHubAdvertiser,
) : com.universalmusic.player.data.sync.HomeLanSyncHub by hub {
    override suspend fun start() {
        val owner = checkNotNull(processHubOwnership) { "Desktop hub ownership was not initialized" }
        owner.startHub {
            try {
                hub.start()
                advertiser.start()
            } catch (error: Throwable) {
                advertiser.stop()
                hub.stop()
                throw error
            }
        }
    }

    override suspend fun stop() {
        advertiser.stop()
        hub.stop()
    }
}

actual fun createHomeLanVaultStore(
    vaultRootProvider: () -> String?,
): com.universalmusic.player.data.sync.HomeLanVaultStore =
    com.universalmusic.player.data.sync.JvmHomeLanVaultStore(
        vaultRootProvider = vaultRootProvider,
        statePath = homeLanConfigDir().resolve("vault-sync-state.json"),
    )

actual fun homeLanHubPort(): Int = DesktopIsolation.current.hubPort

actual fun homeLanDeviceName(): String =
    runCatching { java.io.File("/etc/hostname").readText().trim() }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: "Linux PC"

actual suspend fun <T> withVaultSyncForeground(
    label: String,
    block: suspend () -> T,
): T = block()

actual fun setHomeLanHubAutostart(enabled: Boolean) = hubUserService.configure(enabled)

actual fun homeLanHubAutostartEnabled(): Boolean = hubUserService.isEnabled()

data class HubSystemctlResult(val status: Int, val output: String = "")

internal enum class HubStopOutcome { STOPPED, STILL_RUNNING }

private data class HubUnitState(val activeState: String, val jobId: UInt) {
    val stopOutcome: HubStopOutcome?
        get() = when {
            jobId != 0u -> null
            activeState == "inactive" || activeState == "failed" -> HubStopOutcome.STOPPED
            activeState == "active" -> HubStopOutcome.STILL_RUNNING
            activeState in setOf("activating", "deactivating", "reloading", "refreshing", "maintenance") -> null
            else -> error("Unknown hub unit activity: $activeState")
        }
}

class HubUserService(
    private val isolation: DesktopIsolation,
    private val configHome: Path,
    private val launcher: () -> String?,
    private val command: (List<String>) -> HubSystemctlResult = ::runHubSystemctl,
) {
    val unitName: String = isolation.instance?.let { "kainos-hub-$it.service" } ?: "kainos-hub.service"
    private val unitFile = configHome.resolve("systemd/user").resolve(unitName)

    fun processMode(hubOnly: Boolean, supervisedUnit: String?): HubProcessMode = when {
        !hubOnly -> HubProcessMode.GUI
        supervisedUnit == unitName -> HubProcessMode.SUPERVISED_HUB_ONLY
        else -> HubProcessMode.HUB_ONLY
    }

    fun isEnabled(): Boolean {
        val result = command(listOf("systemctl", "--user", "is-enabled", unitName))
        return when (result.output.trim()) {
            "enabled", "enabled-runtime" -> {
                check(result.status == 0) { "Could not check $unitName (status ${result.status})" }
                true
            }
            "disabled", "masked", "masked-runtime", "static", "indirect", "linked", "linked-runtime",
            "alias", "generated", "transient", "not-found" -> false
            else -> error("Could not check $unitName (status ${result.status})")
        }
    }

    fun stop() {
        check(command(listOf("systemctl", "--user", "stop", unitName)).status == 0) { "Could not stop $unitName" }
    }

    internal suspend fun reconcileStop(): HubStopOutcome {
        while (true) {
            val result = command(listOf("systemctl", "--user", "show", "--all", "--property=ActiveState", "--property=Job", unitName))
            check(result.status == 0) { "Could not inspect $unitName" }
            val properties = result.output.lineSequence().filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            val job = checkNotNull(properties["Job"]) { "Missing Job for $unitName" }
            val unit = HubUnitState(
                activeState = checkNotNull(properties["ActiveState"]) { "Missing ActiveState for $unitName" },
                jobId = if (job.isEmpty()) 0u else checkNotNull(job.toUIntOrNull()) { "Invalid Job for $unitName" },
            )
            unit.stopOutcome?.let { return it }
            delay(50)
        }
    }

    fun start() {
        check(command(listOf("systemctl", "--user", "start", "--no-block", unitName)).status == 0) {
            "Could not start $unitName"
        }
    }

    fun configure(enabled: Boolean) {
        if (!enabled) {
            check(command(listOf("systemctl", "--user", "disable", "--now", unitName)).status == 0) {
                "Could not disable $unitName"
            }
            Files.deleteIfExists(unitFile)
            reload()
            removeLegacyAutostart()
            return
        }
        val executable = checkNotNull(launcher()) { "Cannot find the Kainos launcher" }
        val body = """
            |[Unit]
            |Description=Kainos home sync hub
            |PartOf=graphical-session.target
            |
            |[Service]
            |Type=simple
            |ExecStart=${unitValue(executable, escapeDollar = true)} --hub-only
            |Environment=${unitValue("KAINOS_DATA_DIR=${isolation.dataDir}")}
            |Environment=${unitValue("KAINOS_HUB_PORT=${isolation.hubPort}")}
            |Environment=${unitValue("KAINOS_HUB_UNIT=$unitName")}
            |${isolation.instance?.let { "Environment=${unitValue("KAINOS_INSTANCE=$it")}" }.orEmpty()}
            |SuccessExitStatus=143
            |Restart=on-failure
            |RestartSec=5
            |
            |[Install]
            |WantedBy=graphical-session.target
            |
        """.trimMargin()
        Files.createDirectories(unitFile.parent)
        if (!Files.exists(unitFile) || Files.readString(unitFile) != body) {
            Files.writeString(unitFile, body)
            reload()
        }
        check(command(listOf("systemctl", "--user", "enable", unitName)).status == 0) { "Could not enable $unitName" }
        removeLegacyAutostart()
    }

    private fun reload() {
        check(command(listOf("systemctl", "--user", "daemon-reload")).status == 0) { "Could not reload user units" }
    }

    private fun removeLegacyAutostart() {
        if (isolation.instance == null) Files.deleteIfExists(configHome.resolve("autostart/kainos-home-sync-hub.desktop"))
    }

    companion object {
        fun current(): HubUserService {
            val home = Path.of(System.getProperty("user.home"))
            val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
                ?: home.resolve(".config")
            return HubUserService(DesktopIsolation.current, config, launcher = {
                System.getenv("KAINOS_PLAYER_EXEC")?.takeIf { it.isNotBlank() }
                    ?: listOf(home.resolve(".local/bin/kainos-player"), Path.of("/usr/local/bin/kainos-player"))
                        .firstOrNull(Files::isExecutable)?.toString()
            })
        }
    }
}

private fun unitValue(value: String, escapeDollar: Boolean = false): String {
    require(value.none { it == '\n' || it == '\r' || it == '\u0000' }) { "Invalid systemd unit value" }
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("%", "%%")
    return "\"${if (escapeDollar) escaped.replace("$", "$$") else escaped}\""
}

private fun runHubSystemctl(arguments: List<String>): HubSystemctlResult {
    val process = ProcessBuilder(arguments)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        error("systemctl timed out")
    }
    return HubSystemctlResult(process.exitValue(), process.inputStream.bufferedReader().use { it.readText() })
}

actual suspend fun rematchLocalHeartsAfterVaultSync(): Int {
    // Desktop: local hearts use absolute paths; vault sync keeps those paths stable when
    // the vault root is the music folder. Filename rematch is primarily an Android need.
    return 0
}
