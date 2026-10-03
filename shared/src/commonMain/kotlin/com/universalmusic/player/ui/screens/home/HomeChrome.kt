package com.universalmusic.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.ui.components.ArtworkImage
import com.universalmusic.player.ui.theme.providerColor

@Composable
internal fun PlayFavoritesAction(
    favoriteCount: Int,
    onPlayFavorites: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onPlayFavorites,
        enabled = favoriteCount > 0,
        modifier = modifier,
    ) {
        Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            if (favoriteCount > 0) "Play favorites" else "No favorites yet",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ProviderAttentionLine(
    message: String?,
    onOpenSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (message == null) return
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.then(
            if (onOpenSettings != null) {
                Modifier
                    .clickable(onClick = onOpenSettings)
                    .semantics { contentDescription = message }
            } else {
                Modifier
            },
        ),
    )
}

@Composable
internal fun DiscoverBlock(
    playlist: Playlist,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(
            playlist.artwork,
            playlist.title,
            Modifier.size(72.dp),
            playlist.title,
        )
        Column(Modifier.weight(1f)) {
            Text(
                "New this week",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                playlist.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    busy -> "Loading tracks…"
                    else -> listOfNotNull(
                        playlist.trackCount?.let { "$it tracks" },
                        "Spotify",
                    ).joinToString(" · ")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
internal fun EmptyDoors(
    localState: ProviderState,
    spotifyState: ProviderState,
    youtubeState: ProviderState,
    onOpenSettings: (() -> Unit)?,
    onOpenSearch: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text("Nothing to play yet", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Kainos plays from three places. Open one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        EmptyDoor(
            provider = ProviderId.LOCAL,
            state = localState,
            name = "Local library",
            action = "Add a music folder in Settings",
            onClick = onOpenSettings,
        )
        EmptyDoor(
            provider = ProviderId.SPOTIFY,
            state = spotifyState,
            name = "Spotify",
            action = "Connect in Settings",
            onClick = onOpenSettings,
        )
        EmptyDoor(
            provider = ProviderId.YOUTUBE_MUSIC,
            state = youtubeState,
            name = "YouTube Music",
            action = "Search for anything and play it",
            onClick = onOpenSearch,
        )
    }
}

@Composable
private fun EmptyDoor(
    provider: ProviderId,
    state: ProviderState,
    name: String,
    action: String,
    onClick: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendDot(provider, state)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(
                action,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LegendDot(provider: ProviderId, state: ProviderState) {
    when (state) {
        ProviderState.AVAILABLE -> Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(providerColor(provider.displayName)),
        )
        ProviderState.LOADING -> Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        ProviderState.RATE_LIMITED -> Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error),
        )
        else -> Box(
            Modifier
                .size(8.dp)
                .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
    }
}
