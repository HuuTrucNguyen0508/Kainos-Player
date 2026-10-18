package com.universalmusic.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.universalmusic.player.data.settings.FolioTint
import com.universalmusic.player.data.settings.SkinChoice
import com.universalmusic.player.data.settings.ThemeMode
import com.universalmusic.player.platform.platformLabel
import com.universalmusic.player.ui.theme.LocalFollowingCaelestia
import com.universalmusic.player.ui.theme.folioPalette

@Composable
internal fun SettingsAppearanceSection(presenter: SettingsPreferencesPresenter) {
    val settings by presenter.state.collectAsState()
    val following = LocalFollowingCaelestia.current
    Text("Appearance", style = MaterialTheme.typography.titleMedium)
    SettingToggle("Compact desktop player", settings.compactMode) {
        presenter.change { current -> current.copy(compactMode = it) }
    }
    Text(
        "On desktop, hide the Now Playing side pane and use a compact bar instead. " +
            "Open Now Playing from the bar when you want the full pane. Phone layout is unchanged.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text("Skin", style = MaterialTheme.typography.labelLarge)
    SkinChoice.entries.forEach { skin ->
        AppearanceOption(settings.skin == skin, when (skin) {
            SkinChoice.PLATFORM_DEFAULT -> "Platform default"
            SkinChoice.FOLIO -> "Folio"
            SkinChoice.CAELESTIA -> "Caelestia"
        }) { presenter.change { it.copy(skin = skin) } }
    }
    if (platformLabel() != "Android") {
        SettingToggle("Follow Caelestia theme", settings.followCaelestiaScheme) {
            presenter.change { current -> current.copy(followCaelestiaScheme = it) }
        }
    }
    Text("Light or dark", style = MaterialTheme.typography.labelLarge)
    if (following) Text("Caelestia decides light or dark", style = MaterialTheme.typography.bodySmall)
    ThemeMode.entries.forEach { mode ->
        AppearanceOption(settings.themeMode == mode, mode.name.lowercase().replaceFirstChar { it.uppercase() }, !following) {
            presenter.change { it.copy(themeMode = mode) }
        }
    }
    Text("Tint", style = MaterialTheme.typography.labelLarge)
    Text("Used by Folio and when the Caelestia theme is unavailable or following is off.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val dark = when (settings.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    FolioTint.entries.forEach { tint ->
        val preview = folioPalette(tint, dark)
        val selected = settings.tint == tint
        Row(
            Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton) {
                presenter.change { it.copy(tint = tint) }
            }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadioButton(selected, onClick = null)
            listOf(preview.ground, preview.scheme.primary, preview.accents.teal, preview.accents.pink).forEach { swatch ->
                Box(Modifier.size(18.dp).background(swatch, MaterialTheme.shapes.extraSmall))
            }
            Text(when (tint) {
                FolioTint.FOLIO -> "Folio"
                FolioTint.EVERFOREST -> "Everforest"
                FolioTint.CATPPUCCIN -> "Catppuccin"
                FolioTint.GRUVBOX -> "Gruvbox"
                FolioTint.NORD -> "Nord"
                FolioTint.ROSE_PINE -> "Rosé Pine"
                FolioTint.TOKYO_NIGHT -> "Tokyo Night"
            })
        }
    }
}

@Composable
private fun AppearanceOption(selected: Boolean, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Text(label, Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f))
    }
}
