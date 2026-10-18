package com.universalmusic.player.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.draw.clip
import com.universalmusic.player.ui.components.KainosChip
import com.universalmusic.player.ui.components.LedgerRow
import com.universalmusic.player.ui.components.SectionRule
import com.universalmusic.player.ui.theme.AccentSlot
import com.universalmusic.player.ui.theme.LocalKainosPalette
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
    val palette = LocalKainosPalette.current
    SectionRule("Skin")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SkinChoice.entries.forEach { skin ->
            KainosChip(
                selected = settings.skin == skin,
                onClick = { presenter.change { it.copy(skin = skin) } },
                label = when (skin) {
                    SkinChoice.PLATFORM_DEFAULT -> "Platform default"
                    SkinChoice.FOLIO -> "Folio"
                    SkinChoice.CAELESTIA -> "Caelestia"
                },
                accent = when (skin) {
                    SkinChoice.PLATFORM_DEFAULT -> AccentSlot.Gray
                    SkinChoice.FOLIO -> AccentSlot.Orange
                    SkinChoice.CAELESTIA -> AccentSlot.Purple
                },
            )
        }
    }
    Text(
        "Folio is the phone's paper almanac. Caelestia matches the desktop shell. Platform default picks Folio on Android and Caelestia on the desktop.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (platformLabel() != "Android") {
        SettingToggle("Follow Caelestia theme", settings.followCaelestiaScheme) {
            presenter.change { current -> current.copy(followCaelestiaScheme = it) }
        }
    }
    SectionRule("Light or dark")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ThemeMode.entries.forEach { mode ->
            KainosChip(
                selected = settings.themeMode == mode,
                onClick = { presenter.change { it.copy(themeMode = mode) } },
                label = mode.name.lowercase().replaceFirstChar { it.uppercase() },
                enabled = !following,
            )
        }
    }
    if (following) {
        Text("Caelestia decides light or dark", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SectionRule("Tint") {
        Text("same set as the launcher", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(
        "Used by Folio, and by Caelestia when the shell theme is unavailable or following is off.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val dark = when (settings.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    Column(Modifier.selectableGroup()) {
        FolioTint.entries.forEach { tint ->
            val preview = folioPalette(tint, dark)
            val selected = settings.tint == tint
            LedgerRow(
                modifier = Modifier.selectable(selected, role = Role.RadioButton) {
                    presenter.change { it.copy(tint = tint) }
                },
                highlighted = selected,
            ) {
                RadioButton(selected, onClick = null)
                Column(Modifier.weight(1f)) {
                    Text(tint.title, style = MaterialTheme.typography.bodyLarge)
                    Text(tint.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Paper, accent, ink, then three of the tint's accent hues.
                listOf(
                    preview.ground, preview.scheme.primary, preview.scheme.onBackground,
                    preview.accents.teal, preview.accents.pink, preview.accents.yellow,
                ).forEach { swatch ->
                    Box(
                        Modifier.size(18.dp)
                            .clip(MaterialTheme.shapes.extraSmall)
                            .background(swatch)
                            .border(1.dp, palette.hair, MaterialTheme.shapes.extraSmall),
                    )
                }
            }
        }
    }
    SectionRule("Desktop layout")
    SettingToggle("Compact desktop player", settings.compactMode) {
        presenter.change { current -> current.copy(compactMode = it) }
    }
    Text(
        "On desktop, hide the Now Playing side pane and use a compact bar instead. " +
            "Open Now Playing from the bar when you want the full pane. Phone layout is unchanged.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val FolioTint.title: String
    get() = when (this) {
        FolioTint.FOLIO -> "Folio"
        FolioTint.EVERFOREST -> "Everforest"
        FolioTint.CATPPUCCIN -> "Catppuccin"
        FolioTint.GRUVBOX -> "Gruvbox"
        FolioTint.NORD -> "Nord"
        FolioTint.ROSE_PINE -> "Rosé Pine"
        FolioTint.TOKYO_NIGHT -> "Tokyo Night"
    }

private val FolioTint.blurb: String
    get() = when (this) {
        FolioTint.FOLIO -> "The launcher default"
        FolioTint.EVERFOREST -> "Soft green paper"
        FolioTint.CATPPUCCIN -> "Cool pastel"
        FolioTint.GRUVBOX -> "Retro warm"
        FolioTint.NORD -> "Arctic blue-grey"
        FolioTint.ROSE_PINE -> "Dusty rose"
        FolioTint.TOKYO_NIGHT -> "Blue night"
    }
