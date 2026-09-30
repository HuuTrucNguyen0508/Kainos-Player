package com.universalmusic.player.ui

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged

/** Receives (field key, focused) so the app knows whether any text field is being typed into. */
val LocalTextInputFocus = staticCompositionLocalOf<(Any, Boolean) -> Unit> { { _, _ -> } }

/**
 * Put on every text field. While one is focused, desktop shortcuts (Space = play/pause) and
 * type-to-search stand down so keystrokes reach the field.
 */
fun Modifier.reportsTextInputFocus(): Modifier = composed {
    val report = LocalTextInputFocus.current
    val key = remember { Any() }
    DisposableEffect(key) {
        onDispose { report(key, false) }
    }
    onFocusChanged { report(key, it.isFocused) }
}
