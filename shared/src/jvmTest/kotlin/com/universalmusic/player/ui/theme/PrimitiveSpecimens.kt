package com.universalmusic.player.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.playlist.PersistedKainosPlaylist
import com.universalmusic.player.data.playlist.PersistedPlaylistEntry
import com.universalmusic.player.ui.RecordedTrackActions
import com.universalmusic.player.ui.TrackActionNoticePill
import com.universalmusic.player.ui.screens.ActionTrackRow
import com.universalmusic.player.ui.screens.TrackActionsMenuItems
import com.universalmusic.player.ui.screens.TrackMenuExtra
import com.universalmusic.player.ui.screens.TrackMenuPage
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universalmusic.player.data.cache.PlaybackSourceKind
import com.universalmusic.player.data.cache.TrackAvailability
import com.universalmusic.player.data.cache.TrackAvailabilityInfo
import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.ui.components.AccentBadge
import com.universalmusic.player.ui.components.AvailabilityMark
import com.universalmusic.player.ui.components.IconTile
import com.universalmusic.player.ui.components.KainosChip
import com.universalmusic.player.ui.components.LedgerHeaderText
import com.universalmusic.player.ui.components.LedgerRow
import com.universalmusic.player.ui.components.MiniPlayerBar
import com.universalmusic.player.ui.components.PlaceholderArt
import com.universalmusic.player.ui.components.SectionRule
import com.universalmusic.player.ui.components.SeekKind
import com.universalmusic.player.ui.components.SeekTrack
import com.universalmusic.player.ui.components.SkinSlider
import com.universalmusic.player.ui.components.Slip
import com.universalmusic.player.ui.components.SourceKind
import com.universalmusic.player.ui.components.SourceMark
import com.universalmusic.player.ui.components.TrackRow
import com.universalmusic.player.ui.components.skinGround

@Composable
private fun Page(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().skinGround().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        content()
    }
}

@Composable
internal fun SeekSpecimens() = Page("Seek") {
    Text("Progress, 5:48 at 2:14, playing", style = MaterialTheme.typography.labelMedium)
    SeekTrack(.385f, 348_000L, isPlaying = true)
    Text("Scrubbing", style = MaterialTheme.typography.labelMedium)
    SeekTrack(.6f, 348_000L, isPlaying = true, scrubbing = true)
    Text("Long track, 42 min", style = MaterialTheme.typography.labelMedium)
    SeekTrack(.2f, 2_520_000L, isPlaying = false)
    Text("Unknown length (quiet rail)", style = MaterialTheme.typography.labelMedium)
    SeekTrack(0f, null, isPlaying = false)
    Text("Disabled while buffering", style = MaterialTheme.typography.labelMedium)
    SeekTrack(.4f, 240_000L, isPlaying = false, enabled = false)
    Text("Slider slot and volume", style = MaterialTheme.typography.labelMedium)
    SkinSlider(.5f, {}, durationMs = 200_000L, isPlaying = true)
    SkinSlider(.72f, {}, kind = SeekKind.Level)
    Text("Compact", style = MaterialTheme.typography.labelMedium)
    SeekTrack(.385f, 348_000L, isPlaying = true, kind = SeekKind.Compact)
}

@Composable
internal fun MarkSpecimens() = Page("Marks, chips, badges") {
    SectionRule("Sources")
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        SourceKind.entries.forEach { SourceMark(it, it.name) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        SourceKind.entries.forEach { SourceMark(it, it.name, showIcon = true) }
    }
    SectionRule("Availability")
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        TrackAvailability.entries.forEach { AvailabilityMark(it, it.name.lowercase().replace('_', ' ')) }
    }
    SectionRule("Chips") { TextButton({}) { Text("Edit") } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KainosChip(true, {}, "Favorites only", accent = Accents.heart)
        KainosChip(false, {}, "Local files only", accent = Accents.local)
        KainosChip(false, {}, "Spotify", accent = Accents.spotify)
    }
    SectionRule("Badges")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AccentBadge("CD 16-bit / 44.1 kHz", Accents.cdQuality)
        AccentBadge("Hi-res 24-bit / 96 kHz", Accents.hiRes)
        AccentBadge("Lossy 320 kbps", Accents.lossy)
    }
    SectionRule("Placeholder art")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Saltwater", "Comet", "Ferric", "Low Orbit", "Glass", "Night Bus", "Tideline", "Vespers").forEach {
            PlaceholderArt(it, Modifier.size(56.dp))
        }
    }
}

private fun sampleTrack(id: String, title: String, artist: String, provider: ProviderId) = Track(
    canonicalId = id, title = title, artists = listOf(ArtistRef(artist, artist)), durationMs = 240_000L,
    sources = listOf(PlaybackSource(provider, id, isPlayable = true, handle = PlaybackHandle.Url("file:///$id"))),
)

private fun info(status: TrackAvailability, label: String, progress: Float? = null) =
    TrackAvailabilityInfo(status, label, PlaybackSourceKind.LOCAL_FILE, "Local", progress = progress)

@Composable
internal fun RowSpecimens() = Page("Tiles, ledgers, rows") {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(
            Triple("Late shift", "Kainos playlist", SourceKind.Kainos),
            Triple("Liked Songs", "Spotify", SourceKind.Spotify),
            Triple("Brass Mornings", "Local album", SourceKind.Local),
            Triple("Vinyl rips", "Folder", SourceKind.Folder),
            Triple("Lantern Sessions", "YouTube", SourceKind.YouTube),
        ).forEach { (title, sub, kind) ->
            IconTile(title, sub, kind.slot, {}) { PlaceholderArt(title, it) }
        }
    }
    LedgerRow(header = true) {
        LedgerHeaderText("Title", Modifier.weight(1f)); LedgerHeaderText("Availability", Modifier.width(140.dp)); LedgerHeaderText("Time")
    }
    LedgerRow { Text("Carrier Wave", Modifier.weight(1f)); AvailabilityMark(TrackAvailability.UNAVAILABLE, "Unavailable", Modifier.width(140.dp)); Text("4:18") }
    LedgerRow(highlighted = true) { Text("Saltwater Cathedral", Modifier.weight(1f)); AvailabilityMark(TrackAvailability.AVAILABLE_LOCALLY, "Local, FLAC", Modifier.width(140.dp)); Text("5:48") }
    TrackRow(sampleTrack("a", "Glass Harbour", "The Northern Wires", ProviderId.SPOTIFY), {})
    TrackRow(sampleTrack("b", "Ferric", "Halden & Vos", ProviderId.YOUTUBE_MUSIC), {}, availability = info(TrackAvailability.DOWNLOADING, "Downloading, 62%", .62f))
    TrackRow(sampleTrack("c", "Comet Lines", "Yusuf Adeyemi Quartet", ProviderId.LOCAL), {}, availability = info(TrackAvailability.FAILED, "Failed, retry"))
    TrackRow(sampleTrack("d", "Tideline", "Cassiopeia Reyes", ProviderId.SPOTIFY), {}, compact = true, availability = info(TrackAvailability.WAITING_FOR_NETWORK, "Waiting for Wi-Fi"))
    Box(Modifier.width(420.dp)) {
        MiniPlayerBar("Saltwater Cathedral", "Mira Okonkwo", null, isPlaying = true, providerLabel = "Local", onOpen = {}, onToggle = {}, onNext = {}, progress = .385f, durationMs = 348_000L)
    }
    Slip(Modifier.width(420.dp)) { Text("A slip holds one thing", Modifier.padding(16.dp)) }
}

/** Track rows with the ⋮ menu, both menu pages as they open, and the confirmation that follows. */
@Composable
internal fun TrackMenuSpecimens() = Page("Track menu") {
    val actions = remember {
        RecordedTrackActions().apply {
            favorites.value = setOf("b")
            playlists.value = listOf(
                PersistedKainosPlaylist("p1", "Late shift", entries = List(12) { PersistedPlaylistEntry("e$it", PersistedTrack("x$it", "x")) }),
                PersistedKainosPlaylist("p2", "Road trip", entries = listOf(PersistedPlaylistEntry("e", PersistedTrack("b", "Ferric")))),
                PersistedKainosPlaylist("p3", "Sunday records"),
            )
        }
    }
    val glass = sampleTrack("a", "Glass Harbour", "The Northern Wires", ProviderId.SPOTIFY)
    val ferric = sampleTrack("b", "Ferric", "Halden & Vos", ProviderId.YOUTUBE_MUSIC)
    Column(Modifier.width(560.dp)) {
        ActionTrackRow(glass, actions.presenter, {}, compact = true)
        ActionTrackRow(ferric, actions.presenter, {}, compact = true)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        MenuSheet {
            TrackActionsMenuItems(
                glass, TrackMenuPage.Actions, favorite = false, canQueue = true, playlists = actions.playlists.value,
                extras = listOf(TrackMenuExtra("Open YouTube", Icons.AutoMirrored.Filled.OpenInNew) {}),
                onPage = {}, onPlayNext = {}, onAddToQueue = {}, onToggleFavorite = {}, onAddToPlaylist = {}, onNewPlaylist = {}, onExtra = {},
            )
        }
        MenuSheet {
            TrackActionsMenuItems(
                ferric, TrackMenuPage.Actions, favorite = true, canQueue = false, playlists = actions.playlists.value, extras = emptyList(),
                onPage = {}, onPlayNext = {}, onAddToQueue = {}, onToggleFavorite = {}, onAddToPlaylist = {}, onNewPlaylist = {}, onExtra = {},
            )
        }
        MenuSheet {
            TrackActionsMenuItems(
                ferric, TrackMenuPage.Playlists, favorite = true, canQueue = true, playlists = actions.playlists.value, extras = emptyList(),
                onPage = {}, onPlayNext = {}, onAddToQueue = {}, onToggleFavorite = {}, onAddToPlaylist = {}, onNewPlaylist = {}, onExtra = {},
            )
        }
    }
    TrackActionNoticePill("Playing next: Glass Harbour")
    TrackActionNoticePill("Added to Road trip")
}

/** Stands in for the dropdown popup, which an offscreen scene does not open. */
@Composable
private fun MenuSheet(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 3.dp,
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.width(IntrinsicSize.Max).padding(vertical = 8.dp)) { content() }
    }
}

