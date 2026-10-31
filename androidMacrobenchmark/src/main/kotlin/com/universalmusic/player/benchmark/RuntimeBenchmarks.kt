package com.universalmusic.player.benchmark

import android.content.ContentValues
import android.provider.MediaStore
import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val APP = "com.universalmusic.player"

internal class Journey {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    fun text(value: String) = checkNotNull(device.wait(Until.findObject(By.text(value)), 15000)) { missing("text: $value") }
    private fun missing(selector: String): String {
        val dump = java.io.ByteArrayOutputStream()
        device.dumpWindowHierarchy(dump)
        return "Missing $selector; hierarchy=" + dump.toString("UTF-8")
    }
    fun tab(value: String) {
        text(value)
        device.findObjects(By.text(value)).last().click()
        device.waitForIdle()
    }
    fun home() { text("Home") }
    fun library() {
        tab("Library")
        checkNotNull(device.wait(Until.findObject(By.textStartsWith("Perf track")), 15000))
    }
    fun scroll() {
        repeat(5) { device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
            device.displayWidth / 2, device.displayHeight / 3, 8) }
        device.waitForIdle()
        check(device.findObject(By.textStartsWith("Perf track")) != null) { "Scroll lost library rows" }
    }
    fun nowPlaying() {
        library()
        // UiObject resolves its node for each action. UiObject2 snapshots can go
        // stale whenever the playing-position semantics updates in Compose.
        repeat(8) {
            device.swipe(device.displayWidth / 2, device.displayHeight / 3,
                device.displayWidth / 2, device.displayHeight * 3 / 4, 8)
        }
        device.waitForIdle()
        val row = device.findObject(UiSelector().text("Perf track 000").instance(0))
        check(row.waitForExists(15000)) { missing("Library row 000") }
        fun clickRow(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.text?.toString() == "Perf track 000") {
                var target: android.view.accessibility.AccessibilityNodeInfo = node
                while (!target.isClickable) target = target.parent ?: return false
                return target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            }
            for (i in 0 until node.childCount) if (clickRow(node.getChild(i))) return true
            return false
        }
        check(clickRow(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)) {
            missing("Library row click action")
        }
        checkNotNull(device.wait(Until.findObject(By.desc("Pause")), 15000)) { missing("Pause after Library play") }
        checkNotNull(device.wait(Until.findObject(By.desc("Close now playing")), 5000)) { missing("Now Playing overlay") }
        check(device.findObject(UiSelector().textMatches("0{1,2}:0[1-9]")).waitForExists(10000)) {
            missing("Advancing local playback position")
        }
    }
    fun search() {
        tab("Search")
        device.waitForIdle()
        // Compose replaces its accessibility node while autofocus/IME settles.
        var typed = false
        repeat(3) {
            if (!typed) try {
                checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 15000)).text = "perf test"
                typed = true
            } catch (_: StaleObjectException) { device.waitForIdle() }
        }
        check(typed) { "Search field stayed stale" }
        device.pressBack() // Hide keyboard. No network submission needed for typing journey.
    }
}

class SeedLibrary {
    @Test fun seed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val resolver = context.contentResolver
        val existing = resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media.DISPLAY_NAME), "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?", arrayOf("Perf track %"), null)!!.use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        if (existing.size < 120) {
            val samples = 8000 * 30
            val wav = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray())
                putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(samples * 2)
            }.array()
            repeat(120) { i ->
                if ("Perf track %03d.wav".format(i) in existing) return@repeat
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, "Perf track %03d.wav".format(i))
                    put(MediaStore.Audio.Media.TITLE, "Perf track %03d".format(i))
                    put(MediaStore.Audio.Media.ARTIST, "Synthetic silence")
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/KainosPerf/")
                }
                val uri = checkNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values))
                resolver.openOutputStream(uri)!!.use { it.write(wav) }
            }
        }
        UiDevice.getInstance(instrumentation).executeShellCommand("pm grant $APP android.permission.READ_MEDIA_AUDIO")
        UiDevice.getInstance(instrumentation).executeShellCommand("pm grant $APP android.permission.POST_NOTIFICATIONS")
    }
}

class ProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()
    @Test fun startup() = rule.collect(APP, maxIterations = 5, stableIterations = 2, includeInStartupProfile = true) {
        pressHome(); startActivityAndWait(); Journey().home()
    }
    @Test fun journeys() = rule.collect(APP, maxIterations = 5, stableIterations = 2) {
        pressHome(); startActivityAndWait()
        Journey().apply { home(); library(); scroll(); tab("Home"); nowPlaying(); device.pressBack(); search(); tab("Home") }
    }
}

class RuntimeBenchmarks {
    @get:Rule val rule = MacrobenchmarkRule()
    private fun measure(mode: CompilationMode) = rule.measureRepeated(
        packageName = APP,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 15,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        Journey().apply {
            home()
            Thread.sleep(5000) // Settled paused restore, before any playback journey.
            val meminfo = device.executeShellCommand("dumpsys meminfo $APP")
            val rss = Regex("TOTAL RSS:\\s+(\\d+)").find(meminfo)?.groupValues?.get(1)
            checkNotNull(rss) { "No TOTAL RSS in dumpsys meminfo" }
            android.util.Log.i("KainosPerf", "idle_rss_kib=$rss mode=$mode")
            library(); scroll(); tab("Home"); nowPlaying(); device.pressBack(); search(); tab("Home")
        }
    }
    @Test fun none() = measure(CompilationMode.None())
    @Test fun partial() = measure(CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require, warmupIterations = 0))
}

class RuntimeSmoke {
    private val j = Journey()
    private fun launch() {
        val context = InstrumentationRegistry.getInstrumentation().context
        context.startActivity(context.packageManager.getLaunchIntentForPackage(APP)!!.apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        j.home()
    }
    @Test fun localTransportSettingsAndSync() {
        launch()
        j.nowPlaying()
        j.device.findObject(By.desc("Pause")).click()
        assertNotNull(j.device.wait(Until.findObject(By.desc("Play")), 5000))
        // Seek via the accessible range control, within the Gradle-run test.
        val slider = j.device.findObject(By.clazz("android.widget.SeekBar"))
        checkNotNull(slider) { "No seek control exposed" }.click()
        assertTrue("Seek did not update the paused position", j.device.findObject(
            UiSelector().textMatches("0{1,2}:1[0-9]")).waitForExists(5000))
        j.device.findObject(By.desc("Next")).click()
        assertNotNull(j.device.wait(Until.findObject(By.text("Perf track 001")), 10000))
        j.device.pressBack()
        j.tab("Settings")
        if (j.device.findObject(By.text("Devices and sync")) == null) {
            UiScrollable(UiSelector().scrollable(true).instance(1)).setAsHorizontalList()
                .scrollIntoView(UiSelector().text("Devices and sync"))
        }
        j.text("Devices and sync").click()
        j.text("Enable home sync")
        // No pairing, hub connection, or sync request is made.
        println(j.device.executeShellCommand("dumpsys meminfo $APP"))
    }
    @Test fun seedRetainedFavorite() {
        launch(); j.nowPlaying()
        j.device.findObject(By.desc("Add to favorites"))?.click()
        assertNotNull(j.device.wait(Until.findObject(By.desc("Remove from favorites")), 5000))
    }
    @Test fun verifyRetainedFavorite() {
        launch(); j.nowPlaying()
        assertNotNull(j.device.wait(Until.findObject(By.desc("Remove from favorites")), 5000))
    }
}

class YouTubeSearchSmoke {
    @Test fun searchAndPlay() {
        val j = Journey()
        val context = InstrumentationRegistry.getInstrumentation().context
        context.startActivity(context.packageManager.getLaunchIntentForPackage(APP)!!.apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        j.home(); j.tab("Search")
        val field = j.device.findObject(UiSelector().className("android.widget.EditText"))
        check(field.waitForExists(10000))
        field.setText("Me at the zoo")
        j.device.pressEnter()
        val result = j.device.wait(Until.findObject(By.textContains("Me at the zoo").clazz("android.widget.TextView")), 30000)
        if (result == null) {
            val dump = java.io.ByteArrayOutputStream()
            j.device.dumpWindowHierarchy(dump)
            error("YouTube search produced no matching result; hierarchy=" + dump.toString("UTF-8"))
        }
        result.click()
        assertNotNull(j.device.wait(Until.findObject(By.desc("Pause")), 20000))
        assertTrue(j.device.findObject(UiSelector().textMatches("0{1,2}:0[1-9]")).waitForExists(15000))
    }
}
