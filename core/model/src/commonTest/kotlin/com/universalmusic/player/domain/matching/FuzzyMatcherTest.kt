package com.universalmusic.player.domain.matching

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FuzzyMatcherTest {
    private fun fold(value: String) = TrackNormalizer.fold(value)

    @Test
    fun subsequenceMatchesAcrossWords() {
        assertNotNull(FuzzyMatcher.score("kmn", fold("Kimi no Namae")))
        assertNotNull(FuzzyMatcher.score("seasdie", fold("Seasons die one after another")))
    }

    @Test
    fun everyTokenMustMatchInAnyOrder() {
        val hay = fold("Lemon Kenshi Yonezu STRAY SHEEP")
        assertNotNull(FuzzyMatcher.score("yonezu lem", hay))
        assertNull(FuzzyMatcher.score("yonezu zzz", hay))
    }

    @Test
    fun missingCharactersDoNotMatch() {
        assertNull(FuzzyMatcher.score("xyz", fold("Lemon")))
        assertNull(FuzzyMatcher.score("nomel", fold("Lemon")), "order matters within a token")
    }

    @Test
    fun caseAndAccentsAreIgnoredThroughFold() {
        assertNotNull(FuzzyMatcher.score(fold("CAFE"), fold("Café del Mar")))
    }

    @Test
    fun exactAndWordStartHitsOutrankScatteredOnes() {
        val exact = FuzzyMatcher.score("lemon", fold("Lemon"))!!
        val wordStart = FuzzyMatcher.score("lem", fold("Lemon"))!!
        val scattered = FuzzyMatcher.score("lem", fold("Ballade of the moon"))!!
        assertTrue(exact > scattered)
        assertTrue(wordStart > scattered)
    }

    @Test
    fun blankQueryMatchesEverything() {
        assertEquals(0, FuzzyMatcher.score("", fold("Anything")))
        assertEquals(0, FuzzyMatcher.score("   ", fold("Anything")))
    }

    @Test
    fun cjkTitlesMatchBySubsequence() {
        assertNotNull(FuzzyMatcher.score(fold("季節"), fold("季節は次々死んでいく")))
        assertNotNull(FuzzyMatcher.score(fold("季死"), fold("季節は次々死んでいく")))
    }
}
