package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.universalmusic.player.domain.model.Album
import com.universalmusic.player.domain.model.Artist
import com.universalmusic.player.domain.model.Artwork
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.ui.components.ArtworkImage
import com.universalmusic.player.ui.components.LedgerRow
import com.universalmusic.player.ui.theme.LocalSkinTokens

/** A playlist found by Search: tapping it opens its page. */
@Composable
internal fun PlaylistResultRow(playlist: Playlist, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ResultRow(
        artwork = playlist.artwork,
        title = playlist.title,
        detail = buildString {
            append("${playlist.source.provider.displayName} playlist")
            playlist.ownerName?.takeIf { it.isNotBlank() }?.let { append(" by $it") }
            playlist.trackCount?.let { append(", ${songCount(it)}") }
        },
        onClick = onClick,
        modifier = modifier,
    )
}

/** A Spotify album found by Search: tapping it opens the album's page. */
@Composable
internal fun AlbumResultRow(album: Album, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ResultRow(
        artwork = album.artwork,
        title = album.title,
        detail = buildString {
            append("Album")
            album.artists.joinToString(", ") { it.name }.takeIf { it.isNotBlank() }?.let { append(" by $it") }
            album.year?.let { append(", $it") }
        },
        onClick = onClick,
        modifier = modifier,
    )
}

/** A Spotify artist found by Search: tapping it opens the artist's page. */
@Composable
internal fun ArtistResultRow(artist: Artist, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ResultRow(
        artwork = artist.artwork,
        title = artist.name,
        detail = "Artist",
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
    )
}

@Composable
private fun ResultRow(
    artwork: Artwork?,
    title: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier,
    shape: Shape = RoundedCornerShape(LocalSkinTokens.current.rounding.small),
) {
    LedgerRow(modifier = modifier, onClick = onClick) {
        ArtworkImage(artwork, title, Modifier.size(48.dp), title, shape = shape)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
