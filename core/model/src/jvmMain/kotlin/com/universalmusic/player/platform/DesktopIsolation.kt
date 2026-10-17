package com.universalmusic.player.platform

import com.universalmusic.player.data.sync.HOME_LAN_SYNC_DEFAULT_PORT
import java.nio.file.Files
import java.nio.file.Path

fun mprisBusName(instance: String?): String =
    "org.mpris.MediaPlayer2.kainosplayer" + instance?.let { ".instance$it" }.orEmpty()

/**
 * Per-process overrides that let several desktop instances run side by side:
 * `KAINOS_DATA_DIR`, `KAINOS_HUB_PORT` and `KAINOS_INSTANCE`.
 */
data class DesktopIsolation(
    val dataDir: Path,
    val hubPort: Int,
    val instance: String?,
    val dataDirOverridden: Boolean = false,
    val hubPortOverridden: Boolean = false,
) {
    val isIsolated: Boolean get() = dataDirOverridden || hubPortOverridden || instance != null
    companion object {
        private const val DATA_DIR_VAR = "KAINOS_DATA_DIR"
        private const val HUB_PORT_VAR = "KAINOS_HUB_PORT"
        private const val INSTANCE_VAR = "KAINOS_INSTANCE"

        val current: DesktopIsolation by lazy {
            load(System.getenv(), Path.of(System.getProperty("user.home")))
        }

        fun parse(env: Map<String, String>, userHome: Path): DesktopIsolation {
            fun value(name: String): String? = env[name]?.trim()?.also {
                require(it.isNotEmpty()) { "$name must not be blank" }
            }
            val dataDir = value(DATA_DIR_VAR)?.let { raw ->
                val path = Path.of(raw)
                require(path.isAbsolute) { "$DATA_DIR_VAR must be an absolute path, got '$raw'" }
                path.normalize()
            }
                ?: userHome.resolve(".universal-music-player")
            val hubPort = value(HUB_PORT_VAR)?.let { raw ->
                val port = raw.toIntOrNull()
                require(port != null && port in 1..65535) {
                    "$HUB_PORT_VAR must be a port number from 1 to 65535, got '$raw'"
                }
                port
            } ?: HOME_LAN_SYNC_DEFAULT_PORT
            val instance = value(INSTANCE_VAR)?.also { raw ->
                require(raw.matches(Regex("[A-Za-z0-9_-]+"))) {
                    "$INSTANCE_VAR may contain only letters, digits, '_' and '-', got '$raw'"
                }
            }
            if (instance != null) {
                val busName = mprisBusName(instance)
                require(busName.length <= 255 && busName.split('.').all {
                    it.matches(Regex("[A-Za-z_-][A-Za-z0-9_-]*"))
                }) { "$INSTANCE_VAR must produce a valid D-Bus name of at most 255 characters" }
            }
            return DesktopIsolation(dataDir, hubPort, instance,
                dataDirOverridden = DATA_DIR_VAR in env, hubPortOverridden = HUB_PORT_VAR in env)
        }

        /** [parse], then create the data directory and check that it is writable. */
        fun load(env: Map<String, String>, userHome: Path): DesktopIsolation {
            val isolation = parse(env, userHome)
            val dir = isolation.dataDir
            val created = runCatching { Files.createDirectories(dir) }
            require(created.isSuccess && Files.isWritable(dir)) {
                val source = if (env[DATA_DIR_VAR].isNullOrBlank()) "data directory" else DATA_DIR_VAR
                "$source $dir cannot be created or is not writable" +
                    created.exceptionOrNull()?.let { " (${it::class.simpleName}: ${it.message})" }.orEmpty()
            }
            return isolation
        }
    }
}
