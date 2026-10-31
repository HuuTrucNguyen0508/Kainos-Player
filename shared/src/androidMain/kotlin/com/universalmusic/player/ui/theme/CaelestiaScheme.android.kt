package com.universalmusic.player.ui.theme

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val noScheme = MutableStateFlow<CaelestiaScheme?>(null).asStateFlow()
actual fun watchCaelestiaScheme(scope: CoroutineScope): StateFlow<CaelestiaScheme?> = noScheme
