package com.universalmusic.player.platform

import com.universalmusic.player.data.sync.HOME_LAN_SYNC_DEFAULT_PORT
import java.nio.file.Files
import java.nio.file.Path

/**
 * Per-process overrides that let several desktop instances run side by side:
 * `KAINOS_DATA_DIR`, `KAINOS_HUB_PORT` and `KAINOS_INSTANCE`.
 */
data class DesktopIsolation(
    val dataDir: Path,
    val hubPort: Int,
    val instance: String?,
) {
    companion object {
        const val DATA_DIR_VAR = "KAINOS_DATA_DIR"
        const val HUB_PORT_VAR = "KAINOS_HUB_PORT"
        const val INSTANCE_VAR = "KAINOS_INSTANCE"

        /** Read once per process; desktop main() forces it first so a bad value stops startup. */
        val current: DesktopIsolation by lazy {
            load(System.getenv(), Path.of(System.getProperty("user.home")))
        }

        fun parse(env: Map<String, String>, userHome: Path): DesktopIsolation {
            fun value(name: String) = env[name]?.trim()?.takeIf(String::isNotEmpty)
            val dataDir = value(DATA_DIR_VAR)?.let { Path.of(it).toAbsolutePath().normalize() }
                ?: userHome.resolve(".universal-music-player")
            val hubPort = value(HUB_PORT_VAR)?.let { raw ->
                raw.toIntOrNull()?.takeIf { it in 1..65535 }
                    ?: throw IllegalArgumentException("$HUB_PORT_VAR must be a port number from 1 to 65535, got '$raw'")
            } ?: HOME_LAN_SYNC_DEFAULT_PORT
            val instance = value(INSTANCE_VAR)?.also { raw ->
                // Becomes a D-Bus bus name element, which allows only these characters.
                require(raw.matches(Regex("[A-Za-z0-9_-]+"))) {
                    "$INSTANCE_VAR may contain only letters, digits, '_' and '-', got '$raw'"
                }
            }
            return DesktopIsolation(dataDir, hubPort, instance)
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
