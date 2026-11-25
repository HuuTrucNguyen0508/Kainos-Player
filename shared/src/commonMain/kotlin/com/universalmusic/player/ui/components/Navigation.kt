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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import com.universalmusic.player.ui.theme.LocalNyxSpacing
import com.universalmusic.player.ui.theme.NyxLightTokens
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
    val nyx = LocalSkinTokens.current.skin == Skin.NYX
    val palette = LocalKainosPalette.current
    val container = when {
        nyx -> Color.Transparent
        folio -> palette.sheet
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
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

/**
 * The desktop rail: the places you browse at the top, Settings anchored at the foot below the
 * [footer] slot, so the destinations you move between stay together.
 */
@Composable
fun KainosRail(
    selected: AppDestination,
    onSelect: (AppDestination) -> Unit,
    modifier: Modifier = Modifier,
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    val palette = LocalKainosPalette.current
    val tokens = LocalSkinTokens.current
    val nyx = tokens.skin == Skin.NYX
    val spacing = LocalNyxSpacing.current
    // Frost's rail sits inside its own glass sheet; Nyx sits on the 82% slab over the sky.
    val container = when (tokens.skin) {
        Skin.FOLIO -> palette.ground
        Skin.FROST -> Color.Transparent
        Skin.CAELESTIA -> MaterialTheme.colorScheme.surfaceContainer
        Skin.NYX -> NyxLightTokens.slab
    }
    Column(
        modifier
            .fillMaxHeight()
            .width(if (nyx) spacing.cell * 16 else 88.dp)
            .background(container)
            .padding(vertical = if (nyx) spacing.md else 16.dp)
            .selectableGroup(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (nyx) spacing.xs else 10.dp),
    ) {
        RailMark()
        Spacer(Modifier.height(if (nyx) spacing.md else 8.dp))
        AppDestination.entries.filter { it != AppDestination.Settings }.forEach { item ->
            TabItem(item, item == selected) { onSelect(item) }
        }
        Spacer(Modifier.weight(1f))
        footer()
        TabItem(AppDestination.Settings, selected == AppDestination.Settings) { onSelect(AppDestination.Settings) }
    }
}

/** The "K" mark. On Frost it is etched into the glass: an opaque tile would show one edge and lose the other. */
@Composable
private fun RailMark() {
    val tokens = LocalSkinTokens.current
    val scheme = MaterialTheme.colorScheme
    val frost = tokens.skin == Skin.FROST
    val nyx = tokens.skin == Skin.NYX
    val spacing = LocalNyxSpacing.current
    val shape = RoundedCornerShape(if (nyx) tokens.rounding.normal else tokens.rounding.large)
    Box(
        Modifier
            .size(if (nyx) spacing.cell * 8 else 44.dp)
            .clip(shape)
            .then(
                when {
                    nyx -> Modifier.background(NyxLightTokens.washFullBase).border(1.dp, NyxLightTokens.buttonRing, shape)
                    frost -> Modifier.background(scheme.onSurface.copy(alpha = .08f)).border(1.dp, LocalKainosPalette.current.hair, shape)
                    else -> Modifier.background(scheme.primaryContainer)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "K",
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = tokens.display,
                fontWeight = if (nyx) FontWeight.ExtraBold else FontWeight.Medium,
            ),
            color = if (frost || nyx) scheme.onSurface else scheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun TabItem(item: AppDestination, active: Boolean, onClick: () -> Unit) {
    val palette = LocalKainosPalette.current
    val tokens = LocalSkinTokens.current
    val scheme = MaterialTheme.colorScheme
    if (tokens.skin == Skin.NYX) {
        val spacing = LocalNyxSpacing.current
        val shape = RoundedCornerShape(tokens.rounding.normal)
        Column(
            Modifier
                .widthIn(min = maxOf(spacing.cell * 12, 48.dp))
                .heightIn(min = maxOf(spacing.cell * 11, 48.dp))
                .then(if (active) Modifier.nyxWash(shape, full = true) else Modifier.clip(shape))
                .selectable(active, onClick = onClick, role = Role.Tab)
                .padding(horizontal = spacing.xs, vertical = spacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
        ) {
            Icon(
                item.icon,
                contentDescription = null,
                tint = if (active) NyxLightTokens.highlight else scheme.onSurfaceVariant,
                modifier = Modifier.size(21.dp),
            )
            Text(
                item.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (active) scheme.onSurface else scheme.onSurfaceVariant,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
            )
        }
        return
    }
    val folio = tokens.skin == Skin.FOLIO
    // Frost has no hue: the active tab inverts to an ink pill, as the shell marks its workspace.
    val frost = tokens.skin == Skin.FROST
    val hue = if (frost) scheme.onPrimary else palette.accent(item.accent, AccentUse.Mark, if (folio) palette.tile else scheme.secondaryContainer)
    val pillColor by animateColorAsState(
        when {
            folio -> palette.tile
            active && frost -> scheme.primary
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
