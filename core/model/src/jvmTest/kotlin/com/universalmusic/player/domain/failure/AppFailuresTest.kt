package com.universalmusic.player.domain.failure

import com.universalmusic.player.platform.PlaybackTrace
import com.universalmusic.player.platform.installCrashLogging
import com.universalmusic.player.platform.logged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val STORAGE_MESSAGE =
    "Some saved data could not be loaded or saved. Restart the app to retry. Your database has been kept."
private const val UNEXPECTED_MESSAGE = "Something failed in the background. Details are in the playback log."

class AppFailuresTest {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + AppFailures.handler)

    @BeforeTest @AfterTest fun clear() = AppFailures.dismiss()

    private fun capturedLog(block: () -> Unit): String {
        val original = System.out
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true))
        try { block() } finally { System.setOut(original) }
        return captured.toString()
    }

    private suspend fun capturedSuspendLog(block: suspend () -> Unit): String {
        val original = System.out
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true))
        try { block() } finally { System.setOut(original) }
        return captured.toString()
    }

    private val shownMessage get() = AppFailures.open.value.shown?.message

    @Test fun launchedNonStorageFailureSaysSoAndLogsItsCause() = runTest {
        val log = capturedSuspendLog {
            appScope.launch { throw IllegalArgumentException("spotify said no") }.join()
        }
        assertEquals(UNEXPECTED_MESSAGE, shownMessage)
        assertContains(log, "IllegalArgumentException: spotify said no")
        assertContains(log, "AppFailuresTest")
    }

    @Test fun launchedStorageFailureKeepsTheStorageMessageAndLogsItsCause() = runTest {
        val log = capturedSuspendLog {
            appScope.launch {
                throw StorageFailure(StorageArea.LIBRARY, IllegalStateException("disk is full"))
            }.join()
        }
        assertEquals(STORAGE_MESSAGE, shownMessage)
        assertContains(log, "Caused by: java.lang.IllegalStateException: disk is full")
    }

    @Test fun storageFailureOutranksAnEarlierAndLaterUnexpectedOne() {
        AppFailures.report(IllegalStateException("first"))
        AppFailures.report(StorageFailure(StorageArea.SETTINGS, IllegalStateException("second")))
        AppFailures.report(IllegalStateException("third"))
        assertEquals(STORAGE_MESSAGE, shownMessage)
    }

    @Test fun cancellationReportsNothing() = runTest {
        val log = capturedSuspendLog {
            AppFailures.report(CancellationException("left the screen"))
            appScope.launch { throw CancellationException("left the screen") }.join()
            assertFailsWith<CancellationException> {
                AppFailures.reporting { throw CancellationException("left the screen") }
            }
        }
        assertTrue(AppFailures.open.value.isEmpty())
        assertEquals("", log)
    }

    @Test fun reportingKeepsGoingAfterAFailureAndKeepsItsRealKind() {
        var reached = false
        AppFailures.reporting { throw IllegalArgumentException("not storage") }
        reached = true
        assertTrue(reached)
        assertEquals(UNEXPECTED_MESSAGE, shownMessage)
    }

    @Test fun dismissClearsEveryOpenFailure() {
        AppFailures.report(IllegalStateException("one"))
        AppFailures.report(StorageFailure(StorageArea.LIBRARY, IllegalStateException("two")))
        AppFailures.dismiss()
        assertNull(shownMessage)
    }

    @Test fun laterSuccessInTheSameAreaClearsOnlyThatArea() {
        AppFailures.report(StorageFailure(StorageArea.LIBRARY, IllegalStateException("locked")))
        AppFailures.report(StorageFailure(StorageArea.PLAYLISTS, IllegalStateException("locked")))
        AppFailures.recovered(StorageArea.LIBRARY)
        assertEquals(STORAGE_MESSAGE, shownMessage)
        AppFailures.recovered(StorageArea.PLAYLISTS)
        assertNull(shownMessage)
    }

    @Test fun successDoesNotHideACorruptRow() {
        AppFailures.report(StorageFailure(StorageArea.CORRUPT_ROWS, IllegalStateException("bad row")))
        AppFailures.recovered(StorageArea.LIBRARY)
        assertEquals(STORAGE_MESSAGE, shownMessage)
    }

    @Test fun unexpectedFailureLeavesTheScreenOnItsOwnButStorageStays() = runTest {
        AppFailures.report(IllegalStateException("one-off"))
        AppFailures.holdOnScreen(AppFailures.open.value.shown)
        assertNull(shownMessage)

        AppFailures.report(StorageFailure(StorageArea.LIBRARY, IllegalStateException("locked")))
        AppFailures.holdOnScreen(AppFailures.open.value.shown)
        assertEquals(STORAGE_MESSAGE, shownMessage)
    }

    @Test fun leavingTheScreenDoesNotTakeAStorageFailureWithIt() = runTest {
        AppFailures.report(IllegalStateException("one-off"))
        AppFailures.report(StorageFailure(StorageArea.LIBRARY, IllegalStateException("locked")))
        AppFailures.holdOnScreen(AppFailure.Unexpected)
        assertEquals(setOf<AppFailure>(AppFailure.Storage(StorageArea.LIBRARY)), AppFailures.open.value)
    }

    @Test fun loggedFailuresDropUrlQueriesThatCanCarryApiKeys() {
        val log = capturedLog {
            PlaybackTrace.failure(
                "Test", "request failed",
                IllegalStateException("timeout [url=https://youtube.example/search?key=SECRET&q=x] retry"),
            )
        }
        assertContains(log, "https://youtube.example/search?<redacted>] retry")
        assertFalse("SECRET" in log)
    }

    @Test fun loggedResultKeepsTheCauseInTheLogAndTheFailureInTheResult() {
        val cause = IllegalStateException("helper exited 3")
        var result: Result<Int>? = null
        val log = capturedLog { result = Result.failure<Int>(cause).logged("Spotify", "discover weekly") }
        assertSame(cause, result?.exceptionOrNull())
        assertContains(log, "[Spotify] discover weekly")
        assertContains(log, "IllegalStateException: helper exited 3")
    }

    @Test fun loggedResultRethrowsCancellationInsteadOfLoggingIt() {
        val log = capturedLog {
            assertFailsWith<CancellationException> {
                Result.failure<Int>(CancellationException("gone")).logged("Spotify", "discover weekly")
            }
        }
        assertEquals("", log)
    }

    @Test fun crashHandlerWritesTheCauseThenLetsThePreviousHandlerRun() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        var forwarded: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, cause -> forwarded = cause }
        try {
            installCrashLogging()
            installCrashLogging()
            val crash = IllegalStateException("window blew up", IllegalArgumentException("root cause"))
            val log = capturedLog {
                Thread({ throw crash }, "kainos-crash-test").apply { start(); join() }
            }
            assertSame(crash, forwarded)
            assertContains(log, "kainos-crash-test")
            assertContains(log, "IllegalStateException: window blew up")
            assertContains(log, "Caused by: java.lang.IllegalArgumentException: root cause")
            assertEquals(1, Regex("window blew up").findAll(log).count())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}
