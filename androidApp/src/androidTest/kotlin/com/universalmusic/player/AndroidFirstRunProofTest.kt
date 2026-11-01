package com.universalmusic.player

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first-run journey, one stage per process. Stages share app data, so only
 * scripts/android-release-gate.py knows their order and when data must be cleared.
 * Screenshots land in /sdcard/Download/kainos-proof for the gate to collect.
 */
@RunWith(AndroidJUnit4::class)
class AndroidFirstRunProofTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val anyPermissionController = Pattern.compile(".*permissioncontroller")
    private val proofDir = "/sdcard/Download/kainos-proof"

    private fun settle() {
        device.waitForIdle(1_000)
        Thread.sleep(800)
    }

    private fun shot(name: String): File {
        settle()
        val dir = File(context.getExternalFilesDir(null), "first-run-proof").apply { mkdirs() }
        val image = File(dir, "first-run-$name.png")
        // Returns false while a window transition is in flight.
        val taken = (1..5).any { attempt ->
            device.takeScreenshot(image) || run { Thread.sleep(attempt * 500L); false }
        }
        assertTrue("Screenshot failed: $name", taken)
        device.executeShellCommand("mkdir -p $proofDir")
        device.executeShellCommand("cp ${image.absolutePath} $proofDir/")
        return image
    }

    private fun launch() {
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        declinePermissionPrompts()
        assertTrue("App did not reach the foreground", device.wait(Until.hasObject(By.pkg(context.packageName)), 15_000))
    }

    // Declined on purpose: the journey must work on the folder grant alone.
    private fun declinePermissionPrompts() {
        repeat(4) {
            if (!device.wait(Until.hasObject(By.pkg(anyPermissionController)), 5_000)) return
            // A repeat request swaps in permission_deny_and_dont_ask_again_button.
            find(By.res(Pattern.compile(".*:id/permission_deny.*")), "permission deny button").click()
            device.wait(Until.gone(By.pkg(anyPermissionController)), 3_000)
        }
        assertFalse("Permission prompt would not go away", device.hasObject(By.pkg(anyPermissionController)))
    }

    private fun find(selector: BySelector, what: String): UiObject2 {
        val found = device.wait(Until.findObject(selector), 15_000)
        assertNotNull("Missing $what", found)
        return found
    }

    private fun see(text: String) = find(By.textContains(text), "text: $text")

    private fun click(text: String) {
        find(By.text(text), "text: $text").click()
        settle()
    }

    private fun installFixture() {
        val samples = 8_000 * 90
        val wav = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
        }.array()
        val source = File(context.getExternalFilesDir(null), "proof-tone.wav").apply { writeBytes(wav) }
        device.executeShellCommand("mkdir -p /sdcard/Music/KainosProof")
        device.executeShellCommand("cp ${source.absolutePath} /sdcard/Music/KainosProof/proof-tone.wav")
    }

    private fun chooseFixtureFolder() {
        click("Choose a music folder")
        find(By.pkg(Pattern.compile(".*documentsui")), "system folder picker")
        settle()
        device.findObject(By.desc("Show roots"))?.let { it.click(); settle() }
        val rootLabels = listOf(Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME), Build.MODEL, "Internal storage")
        rootLabels.filterNotNull().firstNotNullOfOrNull { device.findObject(By.text(it)) }?.let { it.click(); settle() }
        click("Music")
        click("KainosProof")
        find(By.text(Pattern.compile("Use this folder", Pattern.CASE_INSENSITIVE)), "Use this folder").click()
        find(By.text(Pattern.compile("Allow", Pattern.CASE_INSENSITIVE)), "grant confirmation").click()
    }

    private fun openAdvancedSettings() {
        click("Settings")
        if (device.findObject(By.text("Advanced")) == null) {
            UiScrollable(UiSelector().scrollable(true).instance(1)).setAsHorizontalList()
                .scrollIntoView(UiSelector().text("Advanced"))
        }
        click("Advanced")
    }

    @Test fun journey() {
        when (val stage = InstrumentationRegistry.getArguments().getString("stage", "fresh")) {
            "fresh" -> {
                launch()
                see("Where does your music live?")
                shot("fresh")
                click("Choose a music folder")
                find(By.pkg(Pattern.compile(".*documentsui")), "system folder picker")
                shot("picker")
            }
            "grant" -> {
                installFixture()
                launch()
                chooseFixtureFolder()
                see("Your music is ready")
                see("1 song in 1 folder")
                shot("ready")
                click("Play")
                find(By.desc("Pause"), "Pause control after Play")
                see("Lossless")
                Thread.sleep(6_000)
                shot("playing")
            }
            "restore" -> {
                launch()
                see("Resume")
                assertFalse(device.hasObject(By.text("Where does your music live?")))
                find(By.desc("Play"), "paused Play control after restore")
                assertFalse("Restored session must not autoplay", device.hasObject(By.desc("Pause")))
                shot("restored")
            }
            "revoke" -> {
                context.contentResolver.persistedUriPermissions.forEach {
                    context.contentResolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                launch()
                see("Could not read")
                shot("revoked")
            }
            "revokedLaunch" -> {
                launch()
                see("Could not read")
                shot("revoked-relaunch")
            }
            "dark" -> try {
                device.executeShellCommand("cmd uimode night yes")
                launch()
                see("Where does your music live?")
                val image = BitmapFactory.decodeFile(shot("dark").absolutePath)
                val pixel = image.getPixel(image.width / 2, image.height * 3 / 4)
                val luminance = (pixel shr 16 and 0xff) + (pixel shr 8 and 0xff) + (pixel and 0xff)
                assertTrue("Background is not dark: ${Integer.toHexString(pixel)}", luminance < 3 * 80)
            } finally {
                device.executeShellCommand("cmd uimode night no")
            }
            "spotify" -> {
                launch()
                click("Set up Spotify")
                see("Save Client ID")
                assertFalse(device.hasObject(By.textContains("yt-dlp")))
                shot("spotify")
            }
            "youtube" -> {
                launch()
                click("Set up YouTube")
                see("Save API key")
                assertFalse(device.hasObject(By.textContains("yt-dlp")))
                assertFalse(device.hasObject(By.textContains("mpv")))
                shot("youtube")
            }
            "version" -> {
                launch()
                openAdvancedSettings()
                val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_META_DATA)
                val expected = "Version: ${info.versionName} (${info.longVersionCode})"
                assertEquals(expected, see("Version: ").text)
                assertTrue(info.longVersionCode > 1)
                shot("version")
            }
            else -> throw IllegalArgumentException("Unknown stage $stage")
        }
    }
}
