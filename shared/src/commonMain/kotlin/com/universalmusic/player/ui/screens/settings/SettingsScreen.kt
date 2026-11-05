package com.universalmusic.player.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.universalmusic.player.ui.components.KainosChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
internal fun SettingsScreen(container: AppContainer, target: SettingsTarget? = null) {
    val scope = rememberCoroutineScope()
    val preferences = remember(container) { SettingsPreferencesPresenter.from(container, scope) }
    val folders = remember(container) { SettingsFoldersPresenter.from(container, scope) }
    val connect = remember(container) { SettingsConnectPresenter.from(container, scope) }
    val sync = remember(container) { SettingsSyncPresenter.from(container, scope) }
    val advanced = remember(container) { SettingsAdvancedPresenter.from(container, scope) }
    val backup = remember(container) { SettingsBackupPresenter.retainedFor(container) }
    val sources = remember(container) { SourcesPresenter.from(container, scope) }
    SettingsFrame(target) { section ->
        when (section) {
            SettingsSection.Playback -> SettingsPlaybackSection(preferences)
            SettingsSection.Appearance -> SettingsAppearanceSection(preferences)
            SettingsSection.Sources -> SettingsSourcesSection(folders, connect, sources)
            SettingsSection.Devices -> SettingsDevicesSection(preferences, sync)
            SettingsSection.Downloads -> DownloadsSection(container)
            SettingsSection.Backup -> SettingsBackupSection(backup)
            SettingsSection.Advanced -> SettingsAdvancedSection(advanced, sync)
        }
    }
}

/** The section chips and scroller. Opening at [target] selects its section; a later change of target does too. */
@Composable
internal fun SettingsFrame(target: SettingsTarget?, content: @Composable (SettingsSection) -> Unit) {
    var sectionName by rememberSaveable { mutableStateOf((target?.section ?: SettingsSection.Playback).name) }
    LaunchedEffect(target) { target?.let { sectionName = it.section.name } }
    val section = SettingsSection.entries.firstOrNull { it.name == sectionName } ?: SettingsSection.Playback
    CompositionLocalProvider(LocalSettingsArrival provides target) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.displaySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsSection.entries.forEach { item ->
                    KainosChip(section == item, { sectionName = item.name }, item.label, accent = item.accent)
                }
            }
            content(section)
        }
    }
}

@Composable
internal fun SettingsSourcesSection(
    folders: SettingsFoldersPresenter,
    connect: SettingsConnectPresenter,
    sources: SourcesPresenter,
) {
    val readiness by sources.state.collectAsState()
    LaunchedEffect(sources) { sources.recheck() }
    fun of(source: MusicSource) = readiness.first { it.source == source }
    // Setup is read closely, so it keeps a readable measure however wide the window gets.
    Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SettingsFoldersSection(
            folders,
            of(MusicSource.Local),
            audioPlayerMissing = readiness.any { SourceNeed.AudioPlayer in it.needs },
            sources::recheck,
        )
        SettingsSpotifySection(connect, of(MusicSource.Spotify), sources::recheck)
        SettingsYouTubeSection(connect, of(MusicSource.YouTube), sources::recheck)
    }
}
