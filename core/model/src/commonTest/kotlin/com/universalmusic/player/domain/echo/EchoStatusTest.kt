package com.universalmusic.player.domain.echo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class EchoStatusTest {
    private val sample = """
        {
          "version": 1,
          "generated_at_ms": 1793925000000,
          "echoes": [
            {
              "device": "e56a4ca3",
              "state": "playing",
              "last_event_at_ms": 1793924990000,
              "track": {
                "id": "e7d10a9f301fdf63d624218bf8f34899",
                "title": "88θ68nostalgia",
                "artist": "mamomo - Topic",
                "art_url": "http://127.0.0.1:8788/art/e7d10a9f301fdf63d624218bf8f34899.jpg",
                "duration_ms": 201000
              },
              "position_ms": 12000,
              "position_at_ms": 1793924990000,
              "shuffle": true,
              "loop": true,
              "repeat_one": false,
              "up_next": [{"id": "a1", "title": "me & u", "artist": "succducc", "duration_ms": 180000}]
            }
          ]
        }
    """.trimIndent()

    private val generatedAt = 1_793_925_000_000L

    private fun echo(
        state: EchoPlayState = EchoPlayState.PLAYING,
        durationMs: Long? = 201_000,
        lastEventAtMs: Long = generatedAt,
        positionMs: Long = 12_000,
        positionAtMs: Long = generatedAt,
    ) = EchoDevice(
        device = "e",
        state = state,
        lastEventAtMs = lastEventAtMs,
        track = EchoTrack("t", "Song", "Artist", null, durationMs),
        positionMs = positionMs,
        positionAtMs = positionAtMs,
        shuffle = false,
        loop = false,
        repeatOne = false,
        upNext = emptyList(),
    )

    @Test
    fun parsesTheGatewaySample() {
        val status = assertNotNull(parseEchoStatus(sample, receivedAtMs = generatedAt))
        assertEquals(generatedAt, status.generatedAtMs)
        val device = status.echoes.single()
        assertEquals(
            EchoDevice(
                device = "e56a4ca3",
                state = EchoPlayState.PLAYING,
                lastEventAtMs = 1_793_924_990_000,
                track = EchoTrack(
                    "e7d10a9f301fdf63d624218bf8f34899",
                    "88θ68nostalgia",
                    "mamomo - Topic",
                    "http://127.0.0.1:8788/art/e7d10a9f301fdf63d624218bf8f34899.jpg",
                    201_000,
                ),
                positionMs = 12_000,
                positionAtMs = 1_793_924_990_000,
                shuffle = true,
                loop = true,
                repeatOne = false,
                upNext = listOf(EchoTrack("a1", "me & u", "succducc", null, 180_000)),
            ),
            device,
        )
    }

    @Test
    fun ignoresUnknownKeys() {
        val body = """{"version":1,"generated_at_ms":5,"extra":{"a":1},"echoes":[{"device":"d","state":"paused",
            "volume":40,"track":{"id":"x","title":"T","codec":"aac","duration_ms":1000},"up_next":[]}]}"""
        val device = assertNotNull(parseEchoStatus(body, 5)).echoes.single()
        assertEquals(EchoPlayState.PAUSED, device.state)
        assertEquals("T", device.track?.title)
    }

    @Test
    fun nullTrackIsIdle() {
        val body = """{"version":1,"generated_at_ms":5,"echoes":[{"device":"d","state":"idle","track":null,"up_next":[]}]}"""
        val status = assertNotNull(parseEchoStatus(body, 5))
        assertEquals(EchoPlayState.IDLE, status.echoes.single().state)
        assertNull(status.echoes.single().track)
        assertNull(status.shownEcho(5))
    }

    @Test
    fun nullDurationStaysUnknown() {
        val body = """{"version":1,"generated_at_ms":5,"echoes":[{"device":"d","state":"playing",
            "track":{"id":"x","title":"T","artist":"A","art_url":null,"duration_ms":null}}]}"""
        val track = assertNotNull(parseEchoStatus(body, 5)).echoes.single().track
        assertNull(track?.durationMs)
        assertNull(track?.artUrl)
    }

    @Test
    fun unknownArtistBecomesNull() {
        val body = """{"version":1,"generated_at_ms":5,"echoes":[{"device":"d","state":"playing",
            "track":{"id":"x","title":"T","artist":"Artiste inconnu"},
            "up_next":[{"id":"y","title":"U","artist":"Artiste inconnu"}]}]}"""
        val device = assertNotNull(parseEchoStatus(body, 5)).echoes.single()
        assertNull(device.track?.artist)
        assertNull(device.upNext.single().artist)
    }

    @Test
    fun otherVersionsAndJunkAreUnavailable() {
        assertNull(parseEchoStatus(sample.replace("\"version\": 1", "\"version\": 2"), 0))
        assertNull(parseEchoStatus("""{"generated_at_ms":5,"echoes":[]}""", 0))
        assertNull(parseEchoStatus("<html>502</html>", 0))
        assertNull(parseEchoStatus("", 0))
    }

    @Test
    fun aBrokenEchoEntryIsSkippedAlone() {
        val body = """{"version":1,"generated_at_ms":5,"echoes":[{"device":"bad","track":"nope"},
            {"device":"ok","state":"playing","track":{"id":"x","title":"T"}}]}"""
        assertEquals(listOf("ok"), assertNotNull(parseEchoStatus(body, 5)).echoes.map { it.device })
    }

    @Test
    fun emptyEchoesShowNothing() {
        assertNull(assertNotNull(parseEchoStatus("""{"version":1,"generated_at_ms":5,"echoes":[]}""", 5)).shownEcho(5))
    }

    @Test
    fun playingPositionRunsOn() {
        assertEquals(12_000 + 7_500, echo().positionAt(generatedAt + 7_500))
    }

    @Test
    fun pausedPositionHolds() {
        assertEquals(12_000, echo(EchoPlayState.PAUSED).positionAt(generatedAt + 60_000))
        assertEquals(12_000, echo(EchoPlayState.STOPPED).positionAt(generatedAt + 60_000))
    }

    @Test
    fun positionClampsToTheDuration() {
        assertEquals(201_000, echo().positionAt(generatedAt + 10 * 60_000))
        assertEquals(0, echo(positionMs = 0).positionAt(generatedAt - 5_000))
        assertEquals(12_000 + 600_000, echo(durationMs = null).positionAt(generatedAt + 600_000))
    }

    @Test
    fun skewIsCorrectedWithTheGatewayClock() {
        // The local clock runs 40 s ahead of the gateway; 3 s pass locally after the reply.
        val status = EchoStatus(generatedAtMs = generatedAt, receivedAtMs = generatedAt + 40_000, echoes = listOf(echo()))
        val gatewayNow = status.gatewayNow(generatedAt + 43_000)
        assertEquals(generatedAt + 3_000, gatewayNow)
        assertEquals(15_000, status.echoes.single().positionAt(gatewayNow))
    }

    @Test
    fun playingIsShownWhateverItsAge() {
        val playing = echo(lastEventAtMs = generatedAt - 3 * 60 * 60_000)
        val status = EchoStatus(generatedAt, generatedAt, listOf(playing))
        assertSame(playing, status.shownEcho(generatedAt))
    }

    @Test
    fun pausedIsShownForThirtyMinutes() {
        val device = echo(EchoPlayState.PAUSED, lastEventAtMs = generatedAt)
        // Local clock runs an hour behind; the window is measured on the gateway clock.
        val status = EchoStatus(generatedAt, generatedAt - 3_600_000, listOf(device))
        assertSame(device, status.shownEcho(generatedAt - 3_600_000 + ECHO_RECENT_WINDOW_MS))
        assertNull(status.shownEcho(generatedAt - 3_600_000 + ECHO_RECENT_WINDOW_MS + 1))
    }

    @Test
    fun stoppedIsNotShown() {
        val status = EchoStatus(generatedAt, generatedAt, listOf(echo(EchoPlayState.STOPPED)))
        assertNull(status.shownEcho(generatedAt))
    }

    @Test
    fun aNewerPauseDoesNotHideAnEchoThatPlays() {
        // The gateway's order: the kitchen paused after the bedroom's last track change.
        val kitchen = echo(EchoPlayState.PAUSED, lastEventAtMs = generatedAt - 1_000)
        val bedroom = echo(lastEventAtMs = generatedAt - 60_000)
        val status = EchoStatus(generatedAt, generatedAt, listOf(kitchen, bedroom))
        assertSame(bedroom, status.shownEcho(generatedAt))
    }

    @Test
    fun withNothingPlayingOnlyTheNewestCounts() {
        val stale = echo(EchoPlayState.PAUSED, lastEventAtMs = generatedAt - 2 * ECHO_RECENT_WINDOW_MS)
        val older = echo(EchoPlayState.PAUSED, lastEventAtMs = generatedAt - 3 * ECHO_RECENT_WINDOW_MS)
        assertNull(EchoStatus(generatedAt, generatedAt, listOf(stale, older)).shownEcho(generatedAt))
    }

    @Test
    fun unknownStateIsIdle() {
        val body = """{"version":1,"generated_at_ms":5,"echoes":[{"device":"d","state":"buffering","track":{"id":"x","title":"T"}}]}"""
        val status = assertNotNull(parseEchoStatus(body, 5))
        assertEquals(EchoPlayState.IDLE, status.echoes.single().state)
        assertNull(status.shownEcho(5))
    }
}
