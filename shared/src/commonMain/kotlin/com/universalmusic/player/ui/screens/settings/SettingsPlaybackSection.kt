package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universalmusic.player.domain.model.SourceSelectionMode
import com.universalmusic.player.platform.platformLabel

@Composable
internal fun SettingsPlaybackSection(presenter: SettingsPreferencesPresenter) {
    val settings by presenter.state.collectAsState()
        Text("Playback", style = MaterialTheme.typography.titleMedium)
        Text("Quality preference", style = MaterialTheme.typography.labelLarge)
        SourceSelectionMode.entries.filterNot { it.name.startsWith("FORCE") }.forEach { mode ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(settings.sourceSelection == mode) {
                        presenter.change { it.copy(sourceSelection = mode) }
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = settings.sourceSelection == mode, onClick = {
                    presenter.change { it.copy(sourceSelection = mode) }
                })
                Text(mode.label(), modifier = Modifier.padding(start = 8.dp))
            }
        }
        Text(
            playbackBehaviourNote(platformLabel()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingToggle(
            "Start the source's radio from Search",
            settings.searchRadioEnabled,
        ) {
            presenter.change { current -> current.copy(searchRadioEnabled = it) }
        }
        Text(
            "A Spotify result continues with Spotify radio and a YouTube result with its YouTube Mix; " +
                "sources are never mixed. Off: the picked track plays, then only same-source results " +
                "from the list. Tracks you queue stay ahead of the radio.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

}

/** Fixed behaviour with no switch in the app, stated per platform because it differs. */
internal fun playbackBehaviourNote(platform: String): String {
    val gapless = if (platform == "Android") {
        "Tracks have a short gap between them on Android."
    } else {
        "Local files play gaplessly, one into the next. Spotify and YouTube tracks have a short gap."
    }
    return "$gapless Spotify uses its Loud volume normalization. Local files and YouTube play at their own level."
}
