package com.universalmusic.player.data.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Names taken from the owner's ~/Music, where 178 of 560 files carry no title tag. */
class LocalFilenameTagsTest {
    private fun assertTags(fileName: String, title: String, artist: String?) =
        assertEquals(FilenameTags(title, artist), filenameTags(fileName), fileName)

    @Test fun y2mateDownloadsLoseThePrefixTheVideoIdAndTheBitrate() {
        assertTags("y2mate.com - re_plus - Solitude_dldnT46oCrs_320kbps.mp3", "Solitude", "re plus")
        assertTags("y2mate.com - succducc - me & u_F6NnGEzQpPQ_320kbps.mp3", "me & u", "succducc")
        // The video id itself may contain an underscore.
        assertTags("y2mate.com - SUMMER NIGHTS CityPop シティポップ 80s Mix_JmRO_0jat5U_320kbps.mp3", "SUMMER NIGHTS CityPop シティポップ 80s Mix", null)
        assertTags("y2mate.com - 「Hi-Tech」[Ryo Arue] Valhalla_GOwlK4Kf3TE_320kbps.mp3", "「Hi-Tech」[Ryo Arue] Valhalla", null)
    }

    @Test fun anEnDashSeparatesArtistFromTitleAndLyricsNoiseIsDropped() {
        assertTags(
            "y2mate.com - CHIKA – Can't Explain It (feat. Charlie Wilson) (Lyrics) [CC]_RnWZ5aCnSrI_320kbps.mp3",
            "Can't Explain It (feat. Charlie Wilson)",
            "CHIKA",
        )
        assertTags("Artist — Title (Official Audio).flac", "Title", "Artist")
        assertTags("Artist － Title [Official Music Video].m4a", "Title", "Artist")
    }

    @Test fun aBitrateSuffixWithoutAVideoIdIsStripped() {
        assertTags("Collapse feat 藍月なくる_320kbps.mp3", "Collapse feat 藍月なくる", null)
    }

    @Test fun underscoresBecomeSpaces() {
        assertTags("A_Different_Sky.flac", "A Different Sky", null)
        assertTags("Voyagin_Star's_Farewell.flac", "Voyagin Star's Farewell", null)
    }

    @Test fun plainArtistTitleNamesAreSplitOnce() {
        assertTags("George Michael - Careless Whisper (Album Version).flac", "Careless Whisper (Album Version)", "George Michael")
        assertTags("y2mate.com - Nightcore - 12345SEX (Lyrics) - UPSAHL_geOXs84N2Ws_320kbps.mp3", "12345SEX - UPSAHL", "Nightcore")
        assertTags("Ado - 唱.flac", "唱", "Ado")
    }

    @Test fun trackNumbersAreDroppedButNamesThatStartWithDigitsAreKept() {
        assertTags("01 - Intro.flac", "Intro", null)
        assertTags("03. Artist - Song.mp3", "Song", "Artist")
        assertTags("1-07 Second Disc Song.flac", "Second Disc Song", null)
        assertTags("2WEI - Ave Maria.flac", "Ave Maria", "2WEI")
        assertTags("1nonly - Moon and Stars.flac", "Moon and Stars", "1nonly")
        assertTags("2-Hours Epic Music _ THE POWER OF EPIC MUSIC.m4a", "2-Hours Epic Music THE POWER OF EPIC MUSIC", null)
        assertTags("2 HOUR MIX _ World's Most Powerful & Epic Battle Music Collection.m4a", "2 HOUR MIX World's Most Powerful & Epic Battle Music Collection", null)
    }

    @Test fun aDashThatIsNotBetweenSpacesIsPartOfTheName() {
        assertTags("【崩坏·战双】Endless Glow ——联动印象曲 Remix by 雷电红叶の鬼铠.mp3", "【崩坏·战双】Endless Glow ——联动印象曲 Remix by 雷电红叶の鬼铠", null)
        assertTags("Heart_of_the_Lahai-Roi.flac", "Heart of the Lahai-Roi", null)
        assertTags("Dawn of Asia -ginkiha mix-.flac", "Dawn of Asia -ginkiha mix-", null)
        assertTags("2-Hours Epic Music _ THE POWER OF EPIC MUSIC - Best Of Collection - Vol.3 -.m4a", "Best Of Collection - Vol.3", "2-Hours Epic Music THE POWER OF EPIC MUSIC")
    }

    @Test fun aNameThatIsOnlyNoiseStillGetsATitle() {
        assertTags("y2mate.com - _dQw4w9WgXcQ_320kbps.mp3", "y2mate.com - dQw4w9WgXcQ 320kbps", null)
        assertTags("Artist - .flac", "Artist", null)
    }

    @Test fun tagValuesParseTheFormsFilesUse() {
        assertEquals(3, parseTagPosition("3/12"))
        assertEquals(7, parseTagPosition(" 07 "))
        assertNull(parseTagPosition("0"))
        assertNull(parseTagPosition("A1"))
        assertEquals(1998, parseTagYear("1998-11-10"))
        assertEquals(2021, parseTagYear("20210709T000000.000Z"))
        assertEquals(2022, parseTagYear("2022"))
        assertNull(parseTagYear("0"))
        assertEquals(listOf("HOYO-MiX", "黄龄"), splitTagArtists("HOYO-MiX;黄龄"))
        assertEquals(listOf("AC/DC"), splitTagArtists(" AC/DC "))
    }
}
