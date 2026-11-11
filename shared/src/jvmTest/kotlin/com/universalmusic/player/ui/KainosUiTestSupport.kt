package com.universalmusic.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.universalmusic.player.data.playlist.PersistedKainosPlaylist
import com.universalmusic.player.data.settings.FolioTint
import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.ui.components.skinGround
import com.universalmusic.player.ui.screens.TrackActionsPresenter
import com.universalmusic.player.ui.theme.KainosTheme
import com.universalmusic.player.ui.theme.Skin
import com.universalmusic.player.ui.theme.folioPalette
import com.universalmusic.player.ui.theme.skinTokens
import kotlinx.coroutines.flow.MutableStateFlow

/** A fixed theme, so tests read neither the host's desktop environment nor its Caelestia scheme. */
@Composable
internal fun TestTheme(content: @Composable () -> Unit) {
    KainosTheme(folioPalette(FolioTint.FOLIO, false), skinTokens(Skin.FOLIO)) {
        Box(Modifier.fillMaxSize().skinGround()) { content() }
    }
}

internal fun localTracks(count: Int): List<Track> = List(count) { index ->
    val title = "Song ${index.toString().padStart(3, '0')}"
    track(title, "Artist", provider = ProviderId.LOCAL, canonicalId = "local:$index")
}

/** Track menu actions that only record what they were asked to do. */
internal class RecordedTrackActions(
    openCollection: ((com.universalmusic.player.ui.screens.CollectionRef) -> Unit)? = null,
    startRadio: ((Track) -> Unit)? = null,
) {
    val calls = mutableListOf<String>()
    val favorites = MutableStateFlow<Set<String>>(emptySet())
    val playlists = MutableStateFlow<List<PersistedKainosPlaylist>>(emptyList())
    val presenter = TrackActionsPresenter(
        favorites = favorites,
        playlists = playlists,
        playNextAction = { calls += "next:${it.canonicalId}" },
        addToQueueAction = { calls += "queue:${it.canonicalId}" },
        toggleFavoriteTrack = { track ->
            val now = track.canonicalId !in favorites.value
            favorites.value = if (now) favorites.value + track.canonicalId else favorites.value - track.canonicalId
            calls += "heart:${track.canonicalId}:$now"
            now
        },
        syncNowPlayingFavorite = { _, _ -> },
        addTracksToPlaylist = { id, tracks ->
            calls += "add:$id:${tracks.joinToString { it.canonicalId }}"
            playlists.value.firstOrNull { it.id == id }
        },
        createPlaylistWith = { name, tracks ->
            calls += "create:$name:${tracks.joinToString { it.canonicalId }}"
            PersistedKainosPlaylist(id = "new", title = name).also { playlists.value = playlists.value + it }
        },
        openCollection = openCollection,
        startRadioAction = startRadio,
    )
}

/** A page stack for screens that never open a page in the test. */
internal fun emptyPages(scope: kotlinx.coroutines.CoroutineScope): com.universalmusic.player.ui.screens.CollectionStack =
    com.universalmusic.player.ui.screens.testCollectionStack(
        scope,
        com.universalmusic.player.ui.screens.testCollectionCatalog(kotlinx.coroutines.Dispatchers.Unconfined),
    )
