package com.universalmusic.player.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class YouTubeFailureClassificationTest {
    private fun assertKind(kind: YouTubeFailureKind, vararg samples: String) =
        samples.forEach { assertEquals(kind, classifyYouTubeFailure(it), it) }

    @Test fun videoProblemsArePermanent() = assertKind(
        YouTubeFailureKind.PERMANENT,
        "ERROR: [youtube] aaaaaaaaaaa: This video is unavailable",
        "ERROR: [youtube] x: Video unavailable. This video has been removed by the uploader",
        "ERROR: [youtube] x: Private video. Sign in if you've been granted access to this video",
        "ERROR: [youtube] x: Sign in to confirm your age. This video may be inappropriate for some users.",
        "ERROR: [youtube] x: Join this channel to get access to members-only content like this video",
        "ERROR: [youtube] x: The uploader has not made this video available in your country",
        "AgeRestrictedContentException: This age-restricted video cannot be watched anonymously",
        "ContentNotAvailableException: Got error: \"This video is unavailable\"",
        "GeographicRestrictionException: Not available in your country",
        "No suitable YouTube match found.",
        "something nobody has seen before",
    )

    @Test fun extractorRotLooksOutdated() = assertKind(
        YouTubeFailureKind.OUTDATED_EXTRACTOR,
        "ERROR: [youtube] x: Sign in to confirm you’re not a bot. Use --cookies-from-browser",
        "ERROR: unable to download video data: HTTP Error 403: Forbidden",
        "WARNING: [youtube] x: nsig extraction failed: Some formats may be missing",
        "ERROR: [youtube] x: Requested format is not available. Use --list-formats for a list of available formats",
        "ERROR: [youtube] x: Unable to extract initial player response; please report this issue on https://github.com/yt-dlp/yt-dlp/issues",
        "ReCaptchaException: reCaptcha Challenge requested",
        "ParsingException: Could not get signature timestamp",
        "SignInConfirmNotBotException: Sign in to confirm that you're not a bot",
    )

    @Test fun networkTroubleIsTransient() = assertKind(
        YouTubeFailureKind.TRANSIENT,
        "ERROR: [youtube] x: Unable to download webpage: <urlopen error [Errno -3] Temporary failure in name resolution>",
        "ERROR: unable to download video data: HTTP Error 503: Service Unavailable",
        "ERROR: [youtube] x: HTTP Error 429: Too Many Requests",
        "SocketTimeoutException: timeout",
        "UnknownHostException: Unable to resolve host \"rr3---sn.googlevideo.com\"",
        "yt-dlp did not finish within 5 min.",
    )

    @Test fun errorTailKeepsErrorLinesAndDropsProgress() {
        val log = """
            [youtube] x: Downloading webpage
            [download]  10.0% of 3.00MiB
            WARNING: [youtube] something minor
            ERROR: [youtube] x: Video unavailable
        """.trimIndent()
        assertEquals("ERROR: [youtube] x: Video unavailable", extractorErrorTail(log))
        assertEquals(
            "WARNING: [youtube] something minor\nlast line",
            extractorErrorTail("[download] 5%\nWARNING: [youtube] something minor\nlast line"),
        )
        assertEquals(600, extractorErrorTail("ERROR: " + "x".repeat(2_000)).length)
    }

    @Test fun failedRunKeepsItsStderrTail() {
        val script = java.nio.file.Files.createTempFile("extractor", ".sh").toFile()
        script.writeText("#!/bin/sh\necho partial\necho 'ERROR: [youtube] x: Video unavailable' >&2\nexit 1\n")
        script.setExecutable(true)
        try {
            val run = ProcessBuilder(script.absolutePath).runWithin(5_000)
            assertEquals(1, run.exitCode)
            assertEquals("ERROR: [youtube] x: Video unavailable", run.stderrTail.trim())
            assertEquals(null, ProcessBuilder(script.absolutePath).readOutputWithin(5_000))
        } finally { script.delete() }
    }
}
