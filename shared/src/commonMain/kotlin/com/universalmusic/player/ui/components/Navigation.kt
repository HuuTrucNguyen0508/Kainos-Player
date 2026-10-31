package com.universalmusic.player.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.universalmusic.player.ui.navigation.AppDestination
import com.universalmusic.player.ui.theme.AccentSlot
import com.universalmusic.player.ui.theme.AccentUse
import com.universalmusic.player.ui.theme.Accents
import com.universalmusic.player.ui.theme.LocalKainosPalette
import com.universalmusic.player.ui.theme.LocalSkinTokens
import com.universalmusic.player.ui.theme.Skin

val AppDestination.icon: ImageVector
    get() = when (this) {
        AppDestination.Home -> Icons.Default.Home
        AppDestination.Search -> Icons.Default.Search
        AppDestination.Library -> Icons.Default.LibraryMusic
        AppDestination.Settings -> Icons.Default.Settings
    }

val AppDestination.accent: AccentSlot
    get() = when (this) {
        AppDestination.Home -> Accents.home
        AppDestination.Search -> Accents.search
        AppDestination.Library -> Accents.library
        AppDestination.Settings -> Accents.settings
    }

/**
 * The phone dock. Folio draws each tab as a launcher icon tile with the tab's coloured underline
 * when active; Caelestia uses a tonal pill behind the active icon, tinted with the tab's hue.
 */
@Composable
fun KainosTabBar(
    selected: AppDestination,
    onSelect: (AppDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val folio = LocalSkinTokens.current.skin == Skin.FOLIO
    val palette = LocalKainosPalette.current
    val container = if (folio) palette.sheet else MaterialTheme.colorScheme.surfaceContainer
    Slip(modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().background(container).padding(vertical = 8.dp).selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            AppDestination.entries.forEach { item ->
                TabItem(item, item == selected) { onSelect(item) }
            }
        }
    }
}

/** The desktop rail with a [footer] slot. */
@Composable
fun KainosRail(
    selected: AppDestination,
    onSelect: (AppDestination) -> Unit,
    modifier: Modifier = Modifier,
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    val palette = LocalKainosPalette.current
    val tokens = LocalSkinTokens.current
    val folio = tokens.skin == Skin.FOLIO
    val container = if (folio) palette.ground else MaterialTheme.colorScheme.surfaceContainer
    Column(
        modifier.fillMaxHeight().width(88.dp).background(container).padding(vertical = 16.dp).selectableGroup(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(tokens.rounding.large)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "K",
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = tokens.display, fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(8.dp))
        AppDestination.entries.forEach { item ->
            TabItem(item, item == selected) { onSelect(item) }
        }
        footer()
    }
}

@Composable
private fun TabItem(item: AppDestination, active: Boolean, onClick: () -> Unit) {
    val palette = LocalKainosPalette.current
    val tokens = LocalSkinTokens.current
    val scheme = MaterialTheme.colorScheme
    val folio = tokens.skin == Skin.FOLIO
    val hue = palette.accent(item.accent, AccentUse.Mark, if (folio) palette.tile else scheme.secondaryContainer)
    val pillColor by animateColorAsState(
        when {
            folio -> palette.tile
            active -> scheme.secondaryContainer
            else -> Color.Transparent
        },
        tween(tokens.motion.effects.durationMs, easing = tokens.motion.effects.easing),
        label = "tabPill",
    )
    Column(
        Modifier
            .clip(RoundedCornerShape(tokens.rounding.normal))
            .selectable(active, onClick = onClick, role = Role.Tab)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val shape = if (folio) RoundedCornerShape(tokens.rounding.large) else tokens.rounding.full
        Box(
            Modifier
                .size(if (folio) 44.dp else 56.dp, if (folio) 44.dp else 32.dp)
                .clip(shape)
                .background(pillColor)
                .then(if (folio) Modifier.border(1.dp, palette.hair, shape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                item.icon,
                contentDescription = null,
                tint = when {
                    active -> hue
                    else -> scheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp),
            )
            if (folio && active) {
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp).size(16.dp, 2.dp).background(hue))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            item.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) scheme.onSurface else scheme.onSurfaceVariant,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
