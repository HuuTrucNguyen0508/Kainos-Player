package com.universalmusic.player.ui.theme

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

actual fun watchDesktopLook(scope: CoroutineScope): StateFlow<DesktopLook> {
    val env = StateFileWatcher(desktopEnvPath(), scope, parse = ::parseDesktopEnv).state
    // "" stands for no wallpaper: the watcher keeps its last value when parse returns null.
    val wallpaper = StateFileWatcher(wallpaperPathFile(), scope) { text -> text?.trim().orEmpty() }.state
    val frostManifest = frostStylePath()
    return combine(env, wallpaper) { skin, wall -> desktopLook(skin, wall?.ifEmpty { null }, frostManifest) }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, DesktopLook(env.value))
}

internal fun parseDesktopEnv(text: String?): Skin =
    if (text?.trim() == "folio") Skin.FOLIO else Skin.CAELESTIA

/** The shell picks its style from the wallpaper, so Frost wins whenever its manifest claims that file. */
internal fun desktopLook(env: Skin?, wallpaper: String?, frostManifest: Path): DesktopLook =
    DesktopLook(if (wallpaper != null && styleClaims(frostManifest, wallpaper)) Skin.FROST else env, wallpaper)

internal fun styleClaims(manifest: Path, wallpaper: String): Boolean = try {
    val root = Json.parseToJsonElement(Files.readString(manifest)) as? JsonObject
    (root?.get("wallpapers") as? JsonArray)?.any { (it as? JsonPrimitive)?.content == wallpaper } == true
} catch (_: Exception) {
    // A missing or half-written manifest means the style is not active.
    false
}

internal fun desktopEnvPath(): Path = caelestiaStatePath("KAINOS_DESKTOP_ENV_FILE", "desktop-env")

internal fun wallpaperPathFile(): Path = caelestiaStatePath("KAINOS_WALLPAPER_PATH_FILE", "wallpaper/path.txt")

internal fun frostStylePath(
    environment: (String) -> String? = System::getenv,
    home: String = System.getProperty("user.home"),
): Path = environment("KAINOS_FROST_STYLE_FILE")?.takeIf { it.isNotBlank() }?.let(Path::of)
    ?: (environment("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of) ?: Path.of(home, ".config"))
        .resolve("quickshell/caelestia/modules/styles/frost/style.json")

internal fun caelestiaStatePath(
    overrideVariable: String,
    filename: String,
    environment: (String) -> String? = System::getenv,
    home: String = System.getProperty("user.home"),
): Path = environment(overrideVariable)?.takeIf { it.isNotBlank() }?.let(Path::of)
    ?: (environment("XDG_STATE_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
        ?: Path.of(home, ".local", "state")).resolve("caelestia").resolve(filename)
