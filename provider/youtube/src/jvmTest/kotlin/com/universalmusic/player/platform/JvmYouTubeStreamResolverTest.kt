package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.QualityTier
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmYouTubeStreamResolverTest {
    @Test
    fun resolvesAudioUrlAndQualityFromYtDlpJson() = runBlocking {
        val script = Files.createTempFile("fake-yt-dlp", ".sh")
        Files.writeString(
            script,
            """
            #!/bin/sh
            echo '{"url":"https://example.test/audio.m4a","abr":160.5,"asr":44100,"acodec":"mp4a.40.2"}'
            """.trimIndent(),
        )
        Files.setPosixFilePermissions(
            script,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        try {
            val resolver = JvmYouTubeStreamResolver { script }
            assertTrue(resolver.isAvailable())
            val resolved = assertNotNull(resolver.resolveAudioUrl("abc123"))
            assertEquals("https://example.test/audio.m4a", resolved.url)
            assertEquals(QualityTier.STANDARD, resolved.quality?.tier)
            assertEquals(160, resolved.quality?.bitrateKbps)
            assertEquals(44_100, resolved.quality?.sampleRateHz)
            assertEquals("mp4a", resolved.quality?.codec)
        } finally {
            Files.deleteIfExists(script)
        }
    }

    @Test
    fun readsTheVideosMixFromYtDlpFlatPlaylistJson() = runBlocking {
        val fixture = Files.createTempFile("yt-dlp-mix", ".json")
        val args = Files.createTempFile("yt-dlp-args", ".txt")
        Files.write(fixture, javaClass.getResourceAsStream("/yt-dlp-mix.json")!!.readBytes())
        val script = Files.createTempFile("fake-yt-dlp", ".sh")
        Files.writeString(script, "#!/bin/sh\necho \"$@\" > '$args'\ncat '$fixture'\n")
        script.toFile().setExecutable(true)
        try {
            val mix = JvmYouTubeStreamResolver { script }.mix("4NRXx6U8ABQ")
            assertEquals(
                listOf("4NRXx6U8ABQ", "34Na4j8AVgA", "XXYlFuWEuKI", "rhTl_OyehF8"),
                mix.map { it.videoId },
            )
            assertEquals(YouTubeMixEntry("34Na4j8AVgA", mix[1].title, "The Weeknd", 274), mix[1])
            assertTrue(mix[1].title.startsWith("The Weeknd - Starboy"))
            val invocation = Files.readString(args)
            assertTrue("--flat-playlist" in invocation, invocation)
            assertTrue("watch?v=4NRXx6U8ABQ&list=RD4NRXx6U8ABQ" in invocation, invocation)
        } finally {
            listOf(fixture, args, script).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun rejectsBlankOrUnsafeVideoIds() = runBlocking {
        val resolver = JvmYouTubeStreamResolver {
            error("binary should not run")
        }
        assertNull(resolver.resolveAudioUrl(" "))
        assertNull(resolver.resolveAudioUrl("ab c"))
        assertNull(resolver.resolveAudioUrl("id\"quote"))
    }
}
