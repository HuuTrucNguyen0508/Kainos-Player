package com.universalmusic.player.domain.matching

import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DuplicateTracksTest {
    @Test
    fun localFileAndSpotifyLikeOfTheSameSongMatch() {
        val local = local("Love in Jakarta (feat. Kelsey Kuan)", "Dept", 215_076)
        val spotify = spotify("Love in Jakarta (feat. Kelsey Kuan)", listOf("DEPT", "Kelsey Kuan"), 215_000)
        assertTrue(DuplicateTracks.sameRecording(local, spotify))
    }

    @Test
    fun realWorldSpellingsOfOneRecordingMatch() {
        val pairs = listOf(
            local("Here Comes The Sun (Remastered 2009)", "The Beatles", 185_733) to
                spotify("Here Comes The Sun - Remastered 2009", listOf("The Beatles"), 185_733),
            local("Here Comes the Sun", "Beatles", 185_000) to
                spotify("Here Comes The Sun - 2019 Mix", listOf("The Beatles"), 185_500),
            local("Bohemian Rhapsody", "Queen", 354_000) to
                spotify("Bohemian Rhapsody - Remastered 2011", listOf("Queen"), 355_000),
            local("Déjà Vu", "Beyoncé feat. Jay-Z", 240_000) to
                spotify("Deja Vu (feat. Jay-Z)", listOf("Beyonce", "JAY-Z"), 241_500),
            local("Crazy in Love", "Beyoncé", 236_000) to
                spotify("Crazy In Love (feat. Jay-Z)", listOf("Beyoncé", "JAY-Z"), 236_200),
            local("Don't Stop Me Now", "Queen", 209_000) to
                youtube("Queen - Don't Stop Me Now (Official Video)", "Queen Official", 210_000),
            local("Midnight City", "M83", 243_000) to
                youtube("Midnight City", "M83 - Topic", 243_900),
            local("Strobe", "deadmau5", null) to
                spotify("Strobe", listOf("deadmau5"), 637_000),
        )
        pairs.forEach { (a, b) ->
            if (b.title == "Here Comes The Sun - 2019 Mix") {
                assertFalse(DuplicateTracks.sameRecording(a, b), "a new mix is not the same recording: ${b.title}")
            } else {
                assertTrue(DuplicateTracks.sameRecording(a, b), "${a.title} / ${b.title}")
            }
        }
    }

    @Test
    fun versionsSameTitleOtherArtistAndFarDurationsStaySeparate() {
        val studio = local("Weird Fishes / Arpeggi", "Radiohead", 318_000)
        val nonMatches = listOf(
            spotify("Weird Fishes / Arpeggi - Live", listOf("Radiohead"), 318_000),
            spotify("Weird Fishes / Arpeggi (Live from the Basement)", listOf("Radiohead"), 330_000),
            spotify("Weird Fishes / Arpeggi - Acoustic", listOf("Radiohead"), 318_000),
            spotify("Weird Fishes / Arpeggi (Four Tet Remix)", listOf("Radiohead"), 318_000),
            spotify("Weird Fishes / Arpeggi - Radio Edit", listOf("Radiohead"), 240_000),
            spotify("Weird Fishes / Arpeggi", listOf("Thom Yorke"), 318_000),
            spotify("Weird Fishes / Arpeggi", listOf("Radiohead"), 360_000),
            spotify("Weird Fishes", listOf("Radiohead"), 318_000),
        )
        nonMatches.forEach { other ->
            assertFalse(DuplicateTracks.sameRecording(studio, other), "${other.title} by ${other.artistLine}")
        }
        // Three seconds is the edge.
        assertTrue(DuplicateTracks.sameRecording(studio, spotify("Weird Fishes/Arpeggi", listOf("Radiohead"), 321_000)))
        assertFalse(DuplicateTracks.sameRecording(studio, spotify("Weird Fishes/Arpeggi", listOf("Radiohead"), 321_001)))
    }

    @Test
    fun sameSourceCopiesAndUnknownArtistsAreNeverGrouped() {
        assertFalse(DuplicateTracks.sameRecording(local("Song", "Band", 200_000), local("Song", "Band", 200_000, id = "local:2")))
        assertFalse(DuplicateTracks.sameRecording(spotify("Song", listOf("Band"), 200_000), spotify("Song", listOf("Band"), 200_000, id = "spotify:2")))
        assertFalse(DuplicateTracks.sameRecording(local("Song", "", 200_000), spotify("Song", emptyList(), 200_000)))
        assertFalse(DuplicateTracks.sameRecording(local("Song", "Unknown Artist", 200_000), spotify("Song", listOf("Unknown Artist"), 200_000)))
    }

    @Test
    fun groupKeepsTheLocalCopyAndHidesEveryStreamOfIt() {
        val file = local("Midnight City", "M83", 243_000)
        val like = spotify("Midnight City", listOf("M83"), 243_500)
        val single = spotify("Midnight City", listOf("M83"), 244_000, id = "spotify:single")
        val upload = youtube("M83 - Midnight City (Official Audio)", "M83VEVO", 242_000)
        val other = spotify("Wait", listOf("M83"), 343_000)

        val groups = DuplicateTracks.group(listOf(like, upload, other, single, file))

        assertEquals(listOf(other, file), groups.visible(listOf(like, upload, other, single, file)))
        assertEquals(3, groups.hiddenCount)
        assertEquals(file.canonicalId, groups.keeper(like.canonicalId))
        assertEquals(setOf(file.canonicalId, like.canonicalId, single.canonicalId, upload.canonicalId), groups.copiesOf(file.canonicalId))
        assertEquals(setOf(upload.canonicalId, file.canonicalId), groups.effectiveFavorites(setOf(upload.canonicalId)))
    }

    @Test
    fun spotifyIsKeptOverYouTubeWhenThereIsNoFile() {
        val like = spotify("Midnight City", listOf("M83"), 243_500)
        val upload = youtube("Midnight City", "M83", 243_000)
        val groups = DuplicateTracks.group(listOf(upload, like))
        assertEquals(listOf(like), groups.visible(listOf(upload, like)))
    }

    @Test
    fun aStreamThatMatchesNoKeeperDurationStaysVisible() {
        val file = local("Song", "Band", 200_000)
        val extended = spotify("Song", listOf("Band"), 420_000)
        val groups = DuplicateTracks.group(listOf(file, extended))
        assertEquals(0, groups.hiddenCount)
    }

    private fun local(title: String, artist: String, durationMs: Long?, id: String = "local:$title") =
        Track(id, title, listOfNotNull(artist.takeIf { it.isNotEmpty() }?.let { ArtistRef("local-artist:$it", it) }), durationMs = durationMs)

    private fun spotify(title: String, artists: List<String>, durationMs: Long?, id: String = "spotify:$title") =
        Track(id, title, artists.map { ArtistRef("spotify-artist:$it", it) }, durationMs = durationMs)

    private fun youtube(title: String, channel: String, durationMs: Long?) =
        Track("yt:$title", title, listOf(ArtistRef("yt-channel:$channel", channel)), durationMs = durationMs)
}
