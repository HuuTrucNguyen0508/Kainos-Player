package com.universalmusic.player.desktop

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.universalmusic.player.app.UiRequest
import com.universalmusic.player.app.ensureAppContainer
import com.universalmusic.player.ui.UniversalMusicApp

fun main() {
    val icon = BitmapPainter(useResource("icon.png", ::loadImageBitmap))
    application {
        val container = ensureAppContainer()
        val state = rememberWindowState(width = 1280.dp, height = 800.dp)
        Window(
            onCloseRequest = ::exitApplication,
            title = "Kainos Player",
            state = state,
            icon = icon,
            onKeyEvent = { event ->
                if (event.type != KeyEventType.KeyDown) return@Window false
                when {
                    event.key == Key.Spacebar -> {
                        container.player.togglePlayPause()
                        true
                    }
                    event.isCtrlPressed && (event.key == Key.F || event.key == Key.K) -> {
                        container.requestUi(UiRequest.FOCUS_SEARCH)
                        true
                    }
                    event.isCtrlPressed && event.key == Key.Q -> {
                        container.requestUi(UiRequest.TOGGLE_QUEUE)
                        true
                    }
                    event.isCtrlPressed && event.key == Key.DirectionRight -> {
                        container.player.skipToNext()
                        true
                    }
                    event.isCtrlPressed && event.key == Key.DirectionLeft -> {
                        container.player.skipToPrevious()
                        true
                    }
                    else -> false
                }
            },
        ) {
            UniversalMusicApp(container)
        }
    }
}
