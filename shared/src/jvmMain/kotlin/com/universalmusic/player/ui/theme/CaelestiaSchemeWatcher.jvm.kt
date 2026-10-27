package com.universalmusic.player.ui.theme

import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

actual fun watchCaelestiaScheme(scope: CoroutineScope): StateFlow<CaelestiaScheme?> =
    StateFileWatcher(caelestiaSchemePath(), scope) { text -> text?.let(::parseCaelestiaScheme) }.state

internal fun caelestiaSchemePath(): Path = caelestiaStatePath("KAINOS_CAELESTIA_SCHEME", "scheme.json")
