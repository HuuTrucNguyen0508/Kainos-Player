package com.universalmusic.player.desktop

import androidx.compose.runtime.LaunchedEffect
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
import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.data.sync.HUB_OWNED_BY_ANOTHER_INSTANCE
import com.universalmusic.player.data.sync.HubOwnership
import com.universalmusic.player.data.sync.HubProcessMode
import com.universalmusic.player.platform.desktopHubOwnership
import com.universalmusic.player.app.UiRequest
import com.universalmusic.player.app.ensureAppContainer
import com.universalmusic.player.platform.DesktopIsolation
import com.universalmusic.player.platform.mprisBusName
import com.universalmusic.player.platform.unbindPlatformMediaControls
import com.universalmusic.player.ui.UniversalMusicApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val isolation = try {
        DesktopIsolation.current
    } catch (e: IllegalArgumentException) {
        System.err.println("kainos-player: ${e.message}")
        exitProcess(2)
    }
    println("Kainos data dir ${isolation.dataDir}, hub port ${isolation.hubPort}, MPRIS ${mprisBusName(isolation.instance)}")
    val guiLock = if (args.none { it == "--hub-only" }) {
        com.universalmusic.player.platform.GuiInstanceLock.acquire(isolation.dataDir) ?: run {
            System.err.println("kainos-player: another GUI is already using ${isolation.dataDir}. Close it before opening another window.")
            exitProcess(75)
        }
    } else null
    val ownership = desktopHubOwnership(hubOnly = args.any { it == "--hub-only" })
    val mode = ownership.mode
    val container = AtomicReference<AppContainer?>()
    val closeMonitor = Any()
    fun closeHub() = synchronized(closeMonitor) {
        runBlocking { ownership.close { container.get()?.homeLanSync?.stopHub() } }
    }
    val shutdown = Thread({ closeHub() }, "kainos-hub-shutdown")
    Runtime.getRuntime().addShutdownHook(shutdown)
    val exitCode = try {
        val acquired = runBlocking { ownership.acquire() }
        if (!acquired) System.err.println(HUB_OWNED_BY_ANOTHER_INSTANCE)
        if (!acquired && mode != HubProcessMode.GUI) {
            75
        } else if (mode != HubProcessMode.GUI) {
            runHubOnly(ensureAppContainer().also(container::set), ownership)
        } else {
            runGui(container::set)
            0
        }
    } finally {
        try { closeHub() } finally { Runtime.getRuntime().removeShutdownHook(shutdown); guiLock?.close() }
    }
    if (exitCode != 0) exitProcess(exitCode)
}

private fun runGui(onContainer: (AppContainer) -> Unit) {
    val icon = BitmapPainter(useResource("icon.png", ::loadImageBitmap))
    application {
        val container = ensureAppContainer().also(onContainer)
        LaunchedEffect(container) {
            container.enableListeningHandoff()
            container.enableGuiPlayer()
        }
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

private fun runHubOnly(
    container: AppContainer,
    ownership: HubOwnership,
): Int = runBlocking {
    while (!container.ready.value) {
        delay(50)
    }
    if (ownership.handoffUnmanagedHub { container.homeLanSync.stopHub() }) return@runBlocking 0
    val settings = container.settings.value
    if (!settings.homeLanSyncEnabled) {
        System.err.println("kainos-player --hub-only: home sync disabled; exiting")
        return@runBlocking 0
    }
    container.homeLanSync.startHubIfNeeded()
    if (!container.homeLanSync.status.value.hubListening) {
        System.err.println("Kainos home sync hub failed to listen: ${container.homeLanSync.status.value.lastError}")
        return@runBlocking 1
    }
    println("Kainos home sync hub listening")
    CompletableDeferred<Int>().await()
}
