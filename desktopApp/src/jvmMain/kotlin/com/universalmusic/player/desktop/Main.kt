package com.universalmusic.player.desktop

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.utf16CodePoint
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
import com.universalmusic.player.platform.unbindPlatformMediaControls
import com.universalmusic.player.ui.UniversalMusicApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

fun main(args: Array<String>) {
    if (args.any { it == "--hub-only" }) {
        runHubOnly()
        return
    }
    val icon = BitmapPainter(useResource("icon.png", ::loadImageBitmap))
    application {
        val container = ensureAppContainer()
        val state = rememberWindowState(width = 1280.dp, height = 800.dp)
        Window(
            onCloseRequest = {
                unbindPlatformMediaControls()
                // Bounded so a wedged disk never blocks quitting.
                runBlocking { withTimeoutOrNull(2_000) { container.flushListeningSession() } }
                exitApplication()
            },
            title = "Kainos Player",
            state = state,
            icon = icon,
            // Preview so Space is not delivered to the last-focused Button / list row.
            onPreviewKeyEvent = { event ->
                if (event.type != KeyEventType.KeyDown) return@Window false
                if (container.textInputFocused.value) return@Window false
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
                    else -> {
                        // Type-to-search: a printable key with nothing focused starts a Library search.
                        val typed = event.typedText() ?: return@Window false
                        container.typeAheadLibrarySearch(typed)
                        true
                    }
                }
            },
            onKeyEvent = { event ->
                if (event.type != KeyEventType.KeyDown) return@Window false
                if (event.key == Key.Escape) {
                    container.requestUi(UiRequest.DISMISS_OVERLAY)
                    return@Window true
                }
                false
            },
        ) {
            UniversalMusicApp(container)
        }
    }
}

/** The character a plain keystroke types, or null for modifiers, shortcuts, controls and Space. */
private fun KeyEvent.typedText(): String? {
    if (isCtrlPressed || isAltPressed || isMetaPressed) return null
    val codePoint = utf16CodePoint
    if (codePoint <= 0 || codePoint == CHAR_UNDEFINED) return null
    if (Character.isISOControl(codePoint) || Character.isWhitespace(codePoint)) return null
    if (!Character.isDefined(codePoint)) return null
    return String(Character.toChars(codePoint))
}

private const val CHAR_UNDEFINED = 0xFFFF

/** Headless hub for login autostart (Phase 3). */
private fun runHubOnly() = runBlocking {
    val container = ensureAppContainer()
    while (!container.ready.value) {
        delay(50)
    }
    val settings = container.settings.value
    if (!settings.homeLanSyncEnabled || settings.homeLanSyncPairing == null) {
        System.err.println("kainos-player --hub-only: home sync not paired/enabled; exiting")
        return@runBlocking
    }
    container.homeLanSync.startHubIfNeeded()
    println("Kainos home sync hub listening (Ctrl+C to quit)")
    CompletableDeferred<Unit>().await()
}
