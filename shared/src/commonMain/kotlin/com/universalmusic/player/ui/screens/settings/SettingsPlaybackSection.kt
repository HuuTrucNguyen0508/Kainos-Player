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
            PLAYBACK_BEHAVIOUR_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingToggle(
            "Even out the volume of local files",
            settings.volumeLevelling,
        ) {
            presenter.change { current -> current.copy(volumeLevelling = it) }
        }
        Text(
            levellingNote(platformLabel()),
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

@Composable
internal fun SettingsLibrarySection(presenter: SettingsPreferencesPresenter) {
    val settings by presenter.state.collectAsState()
    Text("Library", style = MaterialTheme.typography.titleMedium)
    SettingToggle("Hide duplicates", settings.libraryHideDuplicates) {
        presenter.change { current -> current.copy(libraryHideDuplicates = it) }
    }
    Text(
        "When a song you have as a file is also liked on Spotify or hearted from YouTube, Library " +
            "and Home show the file once, and Play all plays it once. Matches need the same title, " +
            "artist and length, so live, acoustic and remixed versions stay separate. A heart on " +
            "either copy shows on the one you see. Search always shows every result.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Fixed behaviour with no switch in the app. */
internal const val PLAYBACK_BEHAVIOUR_NOTE =
    "Local files play gaplessly, one into the next. Spotify and YouTube tracks have a short gap. " +
        "Spotify uses its Loud volume normalization."

internal fun levellingNote(platform: String): String {
    val analysis = if (platform == "Android") "Home sync brings loudness measurements from your desktop." else
        "Local files and downloaded YouTube audio are measured in the background."
    return "Uses ReplayGain tags first, then measured loudness, to play near Spotify's level. " +
        "Quiet tracks are raised only as far as their peaks allow. $analysis " +
        "Files without tags or measurements and streamed YouTube audio play at their own level. " +
        "Now Playing shows the gain applied."
}
