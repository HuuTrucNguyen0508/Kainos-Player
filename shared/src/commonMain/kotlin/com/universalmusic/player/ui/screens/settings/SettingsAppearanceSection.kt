package com.universalmusic.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universalmusic.player.data.settings.AppColorScheme
import com.universalmusic.player.data.settings.ThemeMode
import com.universalmusic.player.ui.theme.colorSchemeFor

@Composable
internal fun SettingsAppearanceSection(presenter: SettingsPreferencesPresenter) {
    val settings by presenter.state.collectAsState()
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        SettingToggle(
            "Compact desktop player",
            settings.compactMode,
        ) {
            presenter.change { current -> current.copy(compactMode = it) }
        }
        Text(
            "On desktop, hide the Now Playing side pane and use a compact bar instead. " +
                "Open Now Playing from the bar when you want the full pane. Phone layout is unchanged.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Light / dark", style = MaterialTheme.typography.labelLarge)
        ThemeMode.entries.forEach { mode ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(settings.themeMode == mode) {
                        presenter.change { it.copy(themeMode = mode) }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = settings.themeMode == mode, onClick = {
                    presenter.change { it.copy(themeMode = mode) }
                })
                Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }, modifier = Modifier.padding(start = 8.dp))
            }
        }
        Text("Color scheme", style = MaterialTheme.typography.labelLarge)
        Text(
            "Palettes from Caelestia shell schemes. Light and dark variants both update when you change mode above.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AppColorScheme.entries.forEach { scheme ->
            val selected = settings.colorScheme == scheme
            val previewDark = when (settings.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            val preview = colorSchemeFor(scheme, previewDark)
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected) {
                        presenter.change { it.copy(colorScheme = scheme) }
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = {
                    presenter.change { it.copy(colorScheme = scheme) }
                })
                Row(
                    Modifier.padding(start = 4.dp, end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(preview.primary, preview.secondary, preview.tertiary, preview.surfaceContainer).forEach { swatch ->
                        Box(
                            Modifier
                                .size(18.dp)
                                .background(swatch, RoundedCornerShape(4.dp)),
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(scheme.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        scheme.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }


}
