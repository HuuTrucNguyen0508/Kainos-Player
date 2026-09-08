package com.universalmusic.player.ui

import androidx.compose.runtime.Composable

/** Platform back: Android system/gesture back; desktop no-op (Escape uses [UiRequest.DISMISS_OVERLAY]). */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
