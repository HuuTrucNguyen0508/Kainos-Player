package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.universalmusic.player.data.cache.DownloadItemState
import com.universalmusic.player.data.cache.DownloadPhase
import com.universalmusic.player.data.cache.DownloadsSnapshot
import com.universalmusic.player.ui.TestTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DownloadsContentTest {
    private fun item(id: String, phase: DownloadPhase, error: String? = null, retryAtMs: Long? = null) =
        DownloadItemState(id, id.removePrefix("yt:").uppercase(), "Artist", phase, errorMessage = error, retryAtMs = retryAtMs)

    private fun snapshot(vararg items: DownloadItemState, issue: String? = null) =
        DownloadsSnapshot(items.toList(), bytesUsed = 0, maxBytes = 1L shl 31, entryCount = 0,
            recentEvictions = emptyList(), extractorIssue = issue)

    @Test fun removedRowsOfferDownloadInsteadOfRemove() = runComposeUiTest {
        val downloaded = mutableListOf<String>()
        setContent {
            TestTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DownloadsContent(snapshot(item("yt:gone", DownloadPhase.NOT_DOWNLOADED)), "Linux", true,
                        DownloadActions(download = { downloaded += it }))
                }
            }
        }
        onNodeWithText("Removed from this device").assertExists()
        onAllNodesWithText("Remove").assertCountEquals(0)
        onAllNodesWithText("Pin").assertCountEquals(0)
        onNodeWithText("Download").performClick()
        assertEquals(listOf("yt:gone"), downloaded)
    }

    @Test fun rowsNameWhatTheyWaitForAndWhy() = runComposeUiTest {
        val now = 1_000_000L
        setContent {
            TestTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DownloadsContent(
                        snapshot(
                            item("yt:wifi", DownloadPhase.WAITING_FOR_WIFI),
                            item("yt:retry", DownloadPhase.QUEUED, "HTTP Error 503: Service Unavailable", now + 120_000),
                            item("yt:dead", DownloadPhase.FAILED, "ERROR: [youtube] dead: Video unavailable"),
                        ),
                        "Android", true, DownloadActions(), now = now,
                    )
                }
            }
        }
        onNodeWithText("Waiting for Wi-Fi").assertExists()
        onNodeWithText("Retrying in about 2 min").assertExists()
        onNodeWithText("HTTP Error 503: Service Unavailable").assertExists()
        onNodeWithText("Retry now").assertExists()
        onNodeWithText("ERROR: [youtube] dead: Video unavailable").assertExists()
        onAllNodesWithText("Waiting for network").assertCountEquals(0)
    }

    @Test fun wifiSwitchIsAndroidOnlyAndReportsChanges() = runComposeUiTest {
        val changes = mutableListOf<Boolean>()

        setContent {
            TestTheme {
                Column {
                    DownloadsContent(snapshot(), "Android", wifiOnly = true, DownloadActions(setWifiOnly = { changes += it }))
                }
            }
        }
        onNodeWithText("Download on Wi-Fi only").assertIsOn().performClick()
        assertEquals(listOf(false), changes)
    }

    @Test fun desktopHasNoWifiSwitch() = runComposeUiTest {
        setContent { TestTheme { Column { DownloadsContent(snapshot(), "Linux", true, DownloadActions()) } } }
        onAllNodesWithText("Download on Wi-Fi only").assertCountEquals(0)
    }

    @Test fun outdatedExtractorShowsThePlatformFixOnce() = runComposeUiTest {
        val reason = "ERROR: [youtube] a: Sign in to confirm you’re not a bot"
        setContent {
            TestTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DownloadsContent(snapshot(item("yt:a", DownloadPhase.FAILED, reason), issue = reason), "Linux", true,
                        DownloadActions())
                }
            }
        }
        onAllNodesWithText(extractorFix(android = false)).assertCountEquals(1)
        onNodeWithText("Update yt-dlp: run scripts/install-yt-dlp.sh, then restart.").assertExists()
    }

    @Test fun androidFixSaysUpdateTheApp() {
        assertEquals("Update Kainos Player to get a newer YouTube extractor.", extractorFix(android = true))
    }
}
