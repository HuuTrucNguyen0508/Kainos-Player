package com.universalmusic.player.ui.theme

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** What the desktop shell shows: the skin it asks apps to match, and its wallpaper file. */
data class DesktopLook(val skin: Skin? = null, val wallpaper: String? = null)

expect fun watchDesktopLook(scope: CoroutineScope): StateFlow<DesktopLook>
