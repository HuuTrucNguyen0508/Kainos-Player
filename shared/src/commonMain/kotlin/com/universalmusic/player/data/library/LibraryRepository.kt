package com.universalmusic.player.data.library

import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class LibraryRepository {
    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _saved = MutableStateFlow<List<Track>>(emptyList())
    val savedTracks: StateFlow<List<Track>> = _saved.asStateFlow()

    private val _recent = MutableStateFlow<List<Track>>(emptyList())
    val recentlyPlayed: StateFlow<List<Track>> = _recent.asStateFlow()

    fun isFavorite(canonicalId: String): Boolean = canonicalId in _favorites.value

    fun toggleFavorite(track: Track): Boolean {
        val nowFavorite = track.canonicalId !in _favorites.value
        _favorites.update { current ->
            if (nowFavorite) current + track.canonicalId else current - track.canonicalId
        }
        if (nowFavorite) {
            remember(track)
        }
        return nowFavorite
    }

    fun remember(track: Track) {
        _saved.update { current ->
            listOf(track) + current.filterNot { it.canonicalId == track.canonicalId }
        }
    }

    fun recordPlay(track: Track) {
        _recent.update { current ->
            listOf(track) + current.filterNot { it.canonicalId == track.canonicalId }.take(40)
        }
        remember(track)
    }
}
