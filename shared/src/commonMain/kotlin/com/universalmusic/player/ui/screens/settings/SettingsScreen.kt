package com.universalmusic.player.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universalmusic.player.app.AppContainer

@Composable
fun SettingsScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val preferences = remember(container) { SettingsPreferencesPresenter.from(container, scope) }
    val folders = remember(container) { SettingsFoldersPresenter.from(container, scope) }
    val connect = remember(container) { SettingsConnectPresenter.from(container, scope) }
    val sync = remember(container) { SettingsSyncPresenter.from(container, scope) }
    val advanced = remember(container) { SettingsAdvancedPresenter.from(container, scope) }
    var sectionName by rememberSaveable { mutableStateOf(SettingsSection.Playback.name) }
    val section = SettingsSection.entries.firstOrNull { it.name == sectionName } ?: SettingsSection.Playback
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsSection.entries.forEach { item ->
                FilterChip(selected = section == item, onClick = { sectionName = item.name }, label = { Text(item.label) })
            }
        }
        when (section) {
            SettingsSection.Playback -> SettingsPlaybackSection(preferences)
            SettingsSection.Appearance -> SettingsAppearanceSection(preferences)
            SettingsSection.Sources -> {
                SettingsFoldersSection(folders)
                SettingsConnectSection(connect)
            }
            SettingsSection.Devices -> SettingsDevicesSection(preferences, sync)
            SettingsSection.Downloads -> DownloadsSection(container)
            SettingsSection.Advanced -> SettingsAdvancedSection(advanced, sync)
        }
    }
}
