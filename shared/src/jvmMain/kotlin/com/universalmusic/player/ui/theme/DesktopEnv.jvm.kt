package com.universalmusic.player.ui.theme

import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

actual fun watchDesktopEnv(scope: CoroutineScope): StateFlow<Skin?> =
    StateFileWatcher(desktopEnvPath(), scope, parse = ::parseDesktopEnv).state

internal fun parseDesktopEnv(text: String?): Skin =
    if (text?.trim() == "folio") Skin.FOLIO else Skin.CAELESTIA

internal fun desktopEnvPath(): Path = caelestiaStatePath("KAINOS_DESKTOP_ENV_FILE", "desktop-env")

internal fun caelestiaStatePath(
    overrideVariable: String,
    filename: String,
    environment: (String) -> String? = System::getenv,
    home: String = System.getProperty("user.home"),
): Path = environment(overrideVariable)?.takeIf { it.isNotBlank() }?.let(Path::of)
    ?: (environment("XDG_STATE_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
        ?: Path.of(home, ".local", "state")).resolve("caelestia").resolve(filename)
