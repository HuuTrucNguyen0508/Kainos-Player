package com.universalmusic.player.ui.screens

import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.data.settings.ThemeMode
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.UnifiedSearchResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SearchPresenterTest {
    @Test
    fun aNewQueryCancelsTheOldSearchAndClearDropsItsResults() = runTest {
        val requests = mutableListOf<String>()
        val completed = mutableListOf<String>()
        val presenter = SearchPresenter(
            settings = MutableStateFlow(AppSettings()),
            spotify = MutableStateFlow(ProviderState.AVAILABLE),
            youtube = MutableStateFlow(ProviderState.NOT_CONFIGURED),
            downloads = MutableStateFlow(emptyMap()),
            search = { query ->
                requests += query
                if (query == "old") delay(1_000)
                completed += query
                emptyResult()
            },
            availability = { error("not used") },
            scope = backgroundScope,
            searchDispatcher = StandardTestDispatcher(testScheduler),
        )
        presenter.setQuery(" old ")
        runCurrent()
        advanceTimeBy(220)
        runCurrent()
        assertEquals(listOf("old"), requests)

        presenter.setQuery("new")
        runCurrent()
        advanceTimeBy(220)
        runCurrent()
        assertEquals(listOf("old", "new"), requests)
        assertEquals(listOf("new"), completed)
        assertEquals("new", presenter.state.value.query)
        assertFalse(presenter.state.value.loading)

        presenter.setQuery("")
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertNull(presenter.state.value.result)
        assertEquals(listOf("new"), completed)
    }

    @Test
    fun providerBecomingConfiguredStartsSearchButUnrelatedUpdatesDoNotRepeatIt() = runTest {
        val prefs = MutableStateFlow(AppSettings())
        val spotify = MutableStateFlow(ProviderState.NOT_CONFIGURED)
        var requests = 0
        val presenter = SearchPresenter(
            settings = prefs,
            spotify = spotify,
            youtube = MutableStateFlow(ProviderState.NOT_CONFIGURED),
            downloads = MutableStateFlow(emptyMap()),
            search = { requests++; emptyResult() },
            availability = { error("not used") },
            scope = backgroundScope,
            searchDispatcher = StandardTestDispatcher(testScheduler),
        )
        presenter.setQuery("song")
        runCurrent()
        advanceTimeBy(220)
        runCurrent()
        assertEquals(0, requests)

        spotify.value = ProviderState.AVAILABLE
        runCurrent()
        advanceTimeBy(220)
        runCurrent()
        assertEquals(1, requests)
        assertTrue(presenter.state.value.providersConfigured)

        prefs.value = prefs.value.copy(themeMode = ThemeMode.DARK)
        spotify.value = ProviderState.RATE_LIMITED
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, requests)

        prefs.value = prefs.value.copy(spotifyClientId = "another-client")
        runCurrent()
        advanceTimeBy(220)
        runCurrent()
        assertEquals(2, requests)
    }
}

private fun emptyResult() = UnifiedSearchResult(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap())
