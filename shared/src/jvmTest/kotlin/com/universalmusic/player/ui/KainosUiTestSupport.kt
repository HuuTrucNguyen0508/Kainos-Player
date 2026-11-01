package com.universalmusic.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.universalmusic.player.data.settings.FolioTint
import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.ui.components.skinGround
import com.universalmusic.player.ui.theme.KainosTheme
import com.universalmusic.player.ui.theme.Skin
import com.universalmusic.player.ui.theme.folioPalette
import com.universalmusic.player.ui.theme.skinTokens

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
