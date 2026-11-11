package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** At this width Settings shows every section at once, in columns, instead of one behind chips. */
internal val SettingsBoardMinWidth: Dp = 1700.dp

/** A board column never gets wider than a comfortable reading measure. */
internal val SettingsBoardColumnMaxWidth: Dp = 700.dp

internal val SettingsBoardGutter: Dp = 40.dp

/**
 * How many board columns Settings gets at its own [width]; 1 means the chip layout. Four start at
 * 2800dp so a 3440 window with the player docked below (a sheet about 2885dp wide) gets all four.
 */
internal fun settingsBoardColumnCount(width: Dp): Int = when {
    width >= 2800.dp -> 4
    width >= 2300.dp -> 3
    width >= SettingsBoardMinWidth -> 2
    else -> 1
}

/**
 * Which sections stand in which column. Fixed, so a section never hops between columns as the
 * window changes within a band: everyday choices first, setup in the middle, upkeep last. The
 * four-column split pairs the short sections so the columns end near the same height: what you
 * hear and keep offline, how it looks, where music comes from, then devices and upkeep.
 */
internal fun settingsBoardColumns(count: Int): List<List<SettingsSection>> = when (count) {
    4 -> listOf(
        listOf(SettingsSection.Playback, SettingsSection.Downloads),
        listOf(SettingsSection.Appearance, SettingsSection.Library),
        listOf(SettingsSection.Sources),
        listOf(SettingsSection.Devices, SettingsSection.Backup, SettingsSection.Advanced),
    )
    3 -> listOf(
        listOf(SettingsSection.Playback, SettingsSection.Library, SettingsSection.Appearance),
        listOf(SettingsSection.Sources, SettingsSection.Downloads),
        listOf(SettingsSection.Devices, SettingsSection.Backup, SettingsSection.Advanced),
    )
    2 -> listOf(
        listOf(SettingsSection.Playback, SettingsSection.Library, SettingsSection.Appearance, SettingsSection.Devices),
        listOf(SettingsSection.Sources, SettingsSection.Downloads, SettingsSection.Backup, SettingsSection.Advanced),
    )
    else -> listOf(SettingsSection.entries)
}

/** Each column's width when [count] columns share [width] with gutters between them. */
internal fun settingsBoardColumnWidth(width: Dp, count: Int): Dp =
    minOf(SettingsBoardColumnMaxWidth, (width - SettingsBoardGutter * (count - 1)) / count)

/** Sections whose content opens with their own heading; the board titles the others. */
internal val SettingsSection.titlesItself: Boolean
    get() = when (this) {
        SettingsSection.Playback, SettingsSection.Library, SettingsSection.Devices, SettingsSection.Downloads,
        SettingsSection.Advanced -> true
        SettingsSection.Appearance, SettingsSection.Sources, SettingsSection.Backup -> false
    }

/**
 * Every section at once, [columnCount] columns under one title and one scroll. [content] learns
 * whether its section is on screen, so work a section starts on entry waits until it is seen.
 */
@Composable
internal fun SettingsBoard(
    innerWidth: Dp,
    columnCount: Int,
    content: @Composable (SettingsSection, visible: Boolean) -> Unit,
) {
    val columnWidth = settingsBoardColumnWidth(innerWidth, columnCount)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.displaySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(SettingsBoardGutter), verticalAlignment = Alignment.Top) {
            settingsBoardColumns(columnCount).forEach { sections ->
                Column(Modifier.width(columnWidth), verticalArrangement = Arrangement.spacedBy(40.dp)) {
                    sections.forEach { section ->
                        key(section) {
                            var visible by remember { mutableStateOf(false) }
                            Column(
                                // boundsInWindow is clipped to the scroller, so an off-screen section measures empty.
                                Modifier.fillMaxWidth().onGloballyPositioned { visible = it.boundsInWindow().height > 0f },
                                verticalArrangement = Arrangement.spacedBy(18.dp),
                            ) {
                                if (!section.titlesItself) {
                                    Text(section.label, style = MaterialTheme.typography.titleMedium)
                                }
                                content(section, visible)
                            }
                        }
                    }
                }
            }
        }
    }
}
