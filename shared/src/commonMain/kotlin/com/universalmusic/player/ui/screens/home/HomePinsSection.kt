package com.universalmusic.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.universalmusic.player.data.library.HomePinKind
import com.universalmusic.player.ui.components.ArtworkImage
import com.universalmusic.player.ui.components.IconTile
import com.universalmusic.player.ui.components.SectionRule
import com.universalmusic.player.ui.components.SourceKind
import com.universalmusic.player.ui.theme.AccentUse
import com.universalmusic.player.ui.theme.Accents
import com.universalmusic.player.ui.theme.LocalKainosPalette
import com.universalmusic.player.ui.theme.LocalSkinTokens
import com.universalmusic.player.ui.theme.Skin

@Composable
internal fun PinnedSection(
    resolvedPins: List<ResolvedHomePin>,
    editingPins: Boolean,
    onToggleEditing: () -> Unit,
    onPlayPin: (ResolvedHomePin) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (resolvedPins.isEmpty()) return
    Column(modifier) {
        SectionRule("Pinned", accent = Accents.playlist) {
            TextButton(onClick = onToggleEditing) {
                Text(if (editingPins) "Done" else "Edit")
            }
        }
        if (!editingPins) {
            FlowRow(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                resolvedPins.forEach { resolved -> PinTile(resolved) { onPlayPin(resolved) } }
            }
        } else {
            resolvedPins.forEachIndexed { index, resolved ->
                PinRow(
                    resolved = resolved,
                    editing = editingPins,
                    canMoveUp = index > 0,
                    canMoveDown = index < resolvedPins.lastIndex,
                    onPlay = { onPlayPin(resolved) },
                    onMoveUp = { onMoveUp(resolved.pin.id) },
                    onMoveDown = { onMoveDown(resolved.pin.id) },
                    onRemove = { onRemove(resolved.pin.id) },
                )
            }
        }
    }
}

/**
 * Real albums, folders and playlists the listener could keep on Home. The tile plays; the pin
 * button on its art keeps it here.
 */
@Composable
internal fun PinSuggestionsSection(
    suggestions: List<PinSuggestion>,
    onPlay: (PinSuggestion) -> Unit,
    onPin: (PinSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (suggestions.isEmpty()) return
    Column(modifier) {
        SectionRule("Pin to Home", accent = Accents.playlist) {
            Text(
                "Tap the pin to keep one here",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            suggestions.forEach { suggestion ->
                SuggestionTile(suggestion, onPlay = { onPlay(suggestion) }, onPin = { onPin(suggestion) })
            }
        }
    }
}

@Composable
private fun SuggestionTile(suggestion: PinSuggestion, onPlay: () -> Unit, onPin: () -> Unit) {
    val palette = LocalKainosPalette.current
    val tokens = LocalSkinTokens.current
    val source = suggestion.kind.source(suggestion.targetId)
    IconTile(
        title = suggestion.title,
        subtitle = suggestion.subtitle,
        accent = source.slot,
        onClick = onPlay,
        width = if (tokens.skin == Skin.FOLIO) 116.dp else 164.dp,
    ) { art ->
        Box(Modifier.fillMaxWidth()) {
            val shape = RoundedCornerShape(tokens.rounding.normal)
            Box(art.align(Alignment.Center)) {
                if (suggestion.artwork != null) {
                    ArtworkImage(suggestion.artwork, suggestion.title, Modifier.fillMaxSize(), suggestion.title, shape = shape)
                } else {
                    val fill = palette.accentContainer(source.slot)
                    Box(Modifier.fillMaxSize().clip(shape).background(fill), contentAlignment = Alignment.Center) {
                        Icon(source.icon, contentDescription = null, tint = palette.accent(source.slot, AccentUse.Mark, fill), modifier = Modifier.size(28.dp))
                    }
                }
            }
            IconButton(
                onClick = onPin,
                modifier = Modifier.align(Alignment.TopEnd).size(48.dp),
            ) {
                Surface(
                    shape = tokens.rounding.full,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .88f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(26.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.PushPin, contentDescription = "Pin ${suggestion.title} to Home", modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

private fun HomePinKind.source(targetId: String): SourceKind = when (this) {
    HomePinKind.KAINOS_PLAYLIST -> SourceKind.Kainos
    HomePinKind.PROVIDER_PLAYLIST -> if (targetId.startsWith("youtube")) SourceKind.YouTube else SourceKind.Spotify
    HomePinKind.ALBUM -> SourceKind.Local
    HomePinKind.LOCAL_FOLDER -> SourceKind.Folder
}

private fun HomePinKind.kindLabel(source: SourceKind): String = when (this) {
    HomePinKind.KAINOS_PLAYLIST -> "Kainos playlist"
    HomePinKind.PROVIDER_PLAYLIST -> if (source == SourceKind.YouTube) "YouTube playlist" else "Spotify playlist"
    HomePinKind.ALBUM -> "Album"
    HomePinKind.LOCAL_FOLDER -> "Folder"
}

@Composable
private fun PinTile(resolved: ResolvedHomePin, onPlay: () -> Unit) {
    val pin = resolved.pin
    val source = pin.kind.source(pin.targetId)
    val palette = LocalKainosPalette.current
    val ready = resolved.status == PinStatus.READY
    val shape = RoundedCornerShape(LocalSkinTokens.current.rounding.normal)
    IconTile(
        title = pin.title.ifBlank { pin.targetId },
        subtitle = if (ready || resolved.status == PinStatus.LOADING) pin.kind.kindLabel(source) else resolved.detail,
        accent = source.slot,
        onClick = { if (ready) onPlay() },
    ) { art ->
        val artwork = pin.artworkUrl?.let { com.universalmusic.player.domain.model.Artwork(it) }
        if (ready && artwork != null) {
            ArtworkImage(artwork, pin.title, art, pin.title, shape = shape)
        } else {
            val fill = palette.accentContainer(source.slot)
            Box(art.clip(shape).background(fill), contentAlignment = Alignment.Center) {
                if (resolved.status == PinStatus.LOADING) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        if (ready) source.icon else Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = if (ready) palette.accent(source.slot, AccentUse.Mark, fill) else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PinRow(
    resolved: ResolvedHomePin,
    editing: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPlay: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val pin = resolved.pin
    val enabled = resolved.status == PinStatus.READY && !editing
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onPlay)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(LocalSkinTokens.current.rounding.normal))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            when (resolved.status) {
                PinStatus.LOADING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else -> Icon(
                    Icons.Default.LibraryMusic,
                    contentDescription = null,
                    tint = when (resolved.status) {
                        PinStatus.READY -> MaterialTheme.colorScheme.primary
                        PinStatus.DISCONNECTED, PinStatus.RATE_LIMITED, PinStatus.ERROR, PinStatus.MISSING ->
                            MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    pin.title.ifBlank { pin.targetId },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                resolved.detail,
                style = MaterialTheme.typography.bodySmall,
                color = when (resolved.status) {
                    PinStatus.READY, PinStatus.EMPTY, PinStatus.LOADING ->
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.error
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (editing) {
            IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = "Unpin ${pin.title}")
            }
        } else if (resolved.status == PinStatus.READY) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Play ${pin.title}",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
