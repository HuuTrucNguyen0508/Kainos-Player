package com.universalmusic.player.ui.screens

import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.app.SourceTools
import com.universalmusic.player.data.library.folderPinDisplayName
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.data.local.LocalScanResult
import kotlinx.coroutines.flow.MutableStateFlow
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.platform.SpotifyReceiverSetup
import com.universalmusic.player.platform.defaultLocalMusicFolder
import com.universalmusic.player.platform.platformLabel
import com.universalmusic.player.platform.supportsMusicFolderPicker
import com.universalmusic.player.ui.components.SourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

internal enum class MusicSource(val title: String, val kind: SourceKind) {
    Local("Local files", SourceKind.Local),
    Spotify("Spotify", SourceKind.Spotify),
    YouTube("YouTube", SourceKind.YouTube),
}

/** A named spot in Settings that a setup action can open directly. */
internal enum class SettingsTarget(val section: SettingsSection) {
    MusicFolders(SettingsSection.Sources),
    AudioPlayer(SettingsSection.Sources),
    SpotifyClientId(SettingsSection.Sources),
    SpotifySignIn(SettingsSection.Sources),
    SpotifyPlayback(SettingsSection.Sources),
    YouTubeKey(SettingsSection.Sources),
    YouTubePlayback(SettingsSection.Sources),
}

/** One thing that stands between a source and sound. */
internal enum class SourceNeed {
    MusicFolder,
    MusicFiles,
    AudioPlayer,
    SpotifyClientId,
    SpotifySignIn,
    SpotifyReceiverInstall,
    SpotifyReceiverSignIn,
    YouTubeKey,
    YouTubeExtractor,
}

internal sealed interface SourceStatus {
    data object Ready : SourceStatus
    data object Checking : SourceStatus

    /** [needs] is never empty and runs in the order the listener has to fix them. */
    data class NeedsSetup(val needs: List<SourceNeed>) : SourceStatus

    /** Set up correctly, but the service or the disk is not answering right now. */
    data object Trouble : SourceStatus
}

internal sealed interface SourceAction {
    data object RetryLocal : SourceAction
    data object PickMusicFolder : SourceAction
    data object OpenLibrary : SourceAction
    data object OpenSearch : SourceAction
    data class OpenSettings(val target: SettingsTarget) : SourceAction
}

internal data class SourceStep(val label: String, val action: SourceAction)

internal data class SourceReadiness(
    val source: MusicSource,
    val status: SourceStatus,
    val statusLabel: String,
    val detail: String,
    val step: SourceStep?,
) {
    val needs: List<SourceNeed> get() = (status as? SourceStatus.NeedsSetup)?.needs.orEmpty()
}

/** The real state of this device. Everything a source claims about itself derives from here. */
internal data class SourceFacts(
    val desktop: Boolean,
    val tools: SourceTools,
    val local: ProviderState,
    val localTrackCount: Int,
    val musicFolders: List<String>,
    val folderPicker: Boolean,
    val spotify: ProviderState,
    val youtube: ProviderState,
    val localScan: LocalScanResult? = null,
)

internal fun sourceReadiness(facts: SourceFacts): List<SourceReadiness> =
    listOf(localReadiness(facts), spotifyReadiness(facts), youtubeReadiness(facts))

private fun pickFolderStep(facts: SourceFacts) = SourceStep(
    "Choose a music folder",
    if (facts.folderPicker) SourceAction.PickMusicFolder else SourceAction.OpenSettings(SettingsTarget.MusicFolders),
)

private fun localReadiness(facts: SourceFacts): SourceReadiness {
    val here = if (facts.desktop) "this computer" else "this phone"
    val player = if (facts.tools.audioPlayer) emptyList() else listOf(SourceNeed.AudioPlayer)
    val missingFolders = facts.localScan?.unavailableRoots ?: facts.musicFolders
    val folderName = facts.musicFolders.lastOrNull()
        ?.takeUnless { facts.localScan?.readableRootCount == 0 && facts.localScan.unavailableRoots.isEmpty() }
        ?.let(::folderPinDisplayName)
    fun readiness(status: SourceStatus, label: String, detail: String, step: SourceStep?) =
        SourceReadiness(MusicSource.Local, status, label, detail, step)
    return when {
        facts.local == ProviderState.LOADING && facts.localTrackCount == 0 -> readiness(
            SourceStatus.Checking,
            "Scanning",
            if (folderName != null) "Looking for music in $folderName." else "Looking for music on $here.",
            null,
        )
        facts.local == ProviderState.UNAVAILABLE -> readiness(
            SourceStatus.Trouble,
            "Could not read",
            if (missingFolders.isEmpty()) "Kainos could not read your music folders. Check folder access and retry."
            else "Cannot read music folders: ${missingFolders.joinToString { folderPinDisplayName(it) }}. Reconnect the drive or check folder access, then retry.",
            SourceStep("Retry", SourceAction.RetryLocal),
        )
        facts.localTrackCount > 0 && player.isNotEmpty() -> readiness(
            SourceStatus.NeedsSetup(player),
            "Needs mpv",
            "${songs(facts.localTrackCount)} found. Kainos plays them through mpv, which is not installed.",
            SourceStep("Install mpv", SourceAction.OpenSettings(SettingsTarget.AudioPlayer)),
        )
        facts.local != ProviderState.LOADING && facts.localScan?.unavailableRoots?.isNotEmpty() == true && facts.localScan.readableRootCount > 0 -> readiness(
            SourceStatus.Ready,
            "Folder disconnected",
            "Some music folders are unavailable: ${facts.localScan.unavailableRoots.joinToString { folderPinDisplayName(it) }}. Other folders are up to date. Reconnect or restore folder access, then retry.",
            SourceStep("Retry", SourceAction.RetryLocal),
        )
        facts.localTrackCount > 0 -> readiness(
            SourceStatus.Ready,
            "Ready",
            "${songs(facts.localTrackCount)} in ${folders(facts.musicFolders.size)}.",
            SourceStep("Open Library", SourceAction.OpenLibrary),
        )
        folderName != null -> readiness(
            SourceStatus.NeedsSetup(listOf(SourceNeed.MusicFiles) + player),
            "No music found",
            "Nothing to play in $folderName. Choose the folder that holds your audio files.",
            pickFolderStep(facts),
        )
        else -> readiness(
            SourceStatus.NeedsSetup(listOf(SourceNeed.MusicFolder) + player),
            "Needs a folder",
            "Audio files on $here. No account needed, and it is the quickest way to start.",
            pickFolderStep(facts),
        )
    }
}

private fun spotifyReadiness(facts: SourceFacts): SourceReadiness {
    val receiver = when (facts.tools.spotifyReceiver) {
        SpotifyReceiverSetup.READY -> emptyList()
        SpotifyReceiverSetup.NEEDS_SIGN_IN -> listOf(SourceNeed.SpotifyReceiverSignIn)
        SpotifyReceiverSetup.NOT_INSTALLED -> listOf(SourceNeed.SpotifyReceiverInstall)
    }
    fun readiness(status: SourceStatus, label: String, detail: String, step: SourceStep?) =
        SourceReadiness(MusicSource.Spotify, status, label, detail, step)
    fun settings(label: String, target: SettingsTarget) = SourceStep(label, SourceAction.OpenSettings(target))
    return when (facts.spotify) {
        ProviderState.NOT_CONFIGURED -> readiness(
            SourceStatus.NeedsSetup(listOf(SourceNeed.SpotifyClientId, SourceNeed.SpotifySignIn) + receiver),
            "Needs a Client ID",
            "Needs your own free Spotify developer Client ID. Playback needs Premium.",
            settings("Set up Spotify", SettingsTarget.SpotifyClientId),
        )
        ProviderState.AUTH_REQUIRED -> readiness(
            SourceStatus.NeedsSetup(listOf(SourceNeed.SpotifySignIn) + receiver),
            "Needs sign-in",
            "Client ID saved. Sign in to load your library. Playback needs Premium.",
            settings("Sign in to Spotify", SettingsTarget.SpotifySignIn),
        )
        ProviderState.LOADING -> readiness(SourceStatus.Checking, "Connecting", "Signing in to Spotify.", null)
        ProviderState.RATE_LIMITED -> readiness(
            SourceStatus.Trouble,
            "Limited",
            "Spotify is limiting requests from your Client ID. Try again in a while.",
            settings("Open Spotify settings", SettingsTarget.SpotifySignIn),
        )
        ProviderState.UNAVAILABLE -> readiness(
            SourceStatus.Trouble,
            "Offline",
            "Kainos cannot reach Spotify right now.",
            settings("Open Spotify settings", SettingsTarget.SpotifySignIn),
        )
        ProviderState.AVAILABLE -> when (receiver.firstOrNull()) {
            SourceNeed.SpotifyReceiverInstall -> readiness(
                SourceStatus.NeedsSetup(receiver),
                "Needs librespot",
                "Signed in. Playing audio needs librespot, which is not installed.",
                settings("Install librespot", SettingsTarget.SpotifyPlayback),
            )
            SourceNeed.SpotifyReceiverSignIn -> readiness(
                SourceStatus.NeedsSetup(receiver),
                "One step left",
                "Signed in. One more sign-in lets Kainos play Spotify audio itself.",
                settings("Set up playback", SettingsTarget.SpotifyPlayback),
            )
            else -> readiness(
                SourceStatus.Ready,
                "Ready",
                "Connected. Your liked songs and playlists are in Library.",
                SourceStep("Open Library", SourceAction.OpenLibrary),
            )
        }
    }
}

private fun youtubeReadiness(facts: SourceFacts): SourceReadiness {
    val player = if (facts.tools.audioPlayer) emptyList() else listOf(SourceNeed.AudioPlayer)
    val extractor = if (facts.tools.youtubeExtractor) emptyList() else listOf(SourceNeed.YouTubeExtractor)
    fun readiness(status: SourceStatus, label: String, detail: String, step: SourceStep?) =
        SourceReadiness(MusicSource.YouTube, status, label, detail, step)
    fun settings(label: String, target: SettingsTarget) = SourceStep(label, SourceAction.OpenSettings(target))
    return when (facts.youtube) {
        ProviderState.NOT_CONFIGURED, ProviderState.AUTH_REQUIRED -> readiness(
            SourceStatus.NeedsSetup(listOf(SourceNeed.YouTubeKey) + extractor + player),
            "Needs a key",
            buildString {
                append("Search needs your own free YouTube Data API key.")
                if (extractor.isNotEmpty()) append(" Playback also needs yt-dlp.")
                if (player.isNotEmpty()) append(" Playback also needs mpv.")
            },
            settings("Set up YouTube", SettingsTarget.YouTubeKey),
        )
        ProviderState.LOADING -> readiness(SourceStatus.Checking, "Connecting", "Talking to YouTube.", null)
        ProviderState.RATE_LIMITED -> readiness(
            SourceStatus.Trouble,
            "Limited",
            "Your YouTube API key has used up its quota for now.",
            settings("Open YouTube settings", SettingsTarget.YouTubeKey),
        )
        ProviderState.UNAVAILABLE -> readiness(
            SourceStatus.Trouble,
            "Offline",
            "Kainos cannot reach YouTube right now.",
            settings("Open YouTube settings", SettingsTarget.YouTubeKey),
        )
        ProviderState.AVAILABLE -> if (extractor.isNotEmpty()) {
            readiness(
                SourceStatus.NeedsSetup(extractor + player),
                "Needs yt-dlp",
                "Search works. Playing audio needs yt-dlp, which is not installed.",
                settings("Install yt-dlp", SettingsTarget.YouTubePlayback),
            )
        } else if (player.isNotEmpty()) {
            readiness(
                SourceStatus.NeedsSetup(player),
                "Needs mpv",
                "Search works. Playing audio needs mpv, which is not installed.",
                settings("Install mpv", SettingsTarget.AudioPlayer),
            )
        } else {
            readiness(
                SourceStatus.Ready,
                "Ready",
                "Search for anything and play it here.",
                SourceStep("Search YouTube", SourceAction.OpenSearch),
            )
        }
    }
}

private fun songs(count: Int) = if (count == 1) "1 song" else "$count songs"
private fun folders(count: Int) = if (count == 1) "1 folder" else "$count folders"

/** Keeps the readiness list current for whichever screen asks. */
internal class SourcesPresenter(
    settings: StateFlow<AppSettings>,
    tools: StateFlow<SourceTools>,
    local: StateFlow<ProviderState>,
    tracks: StateFlow<List<Track>>,
    spotify: StateFlow<ProviderState>,
    youtube: StateFlow<ProviderState>,
    private val desktop: Boolean,
    private val folderPicker: Boolean,
    private val defaultMusicFolder: () -> String,
    private val recheckTools: () -> Unit,
    scope: CoroutineScope,
    localScan: StateFlow<LocalScanResult?> = MutableStateFlow(null),
) {
    val state: StateFlow<List<SourceReadiness>> = combine(
        combine(settings, tools, local, tracks, localScan) { prefs, installed, localState, catalog, scan ->
            DeviceFacts(prefs, installed, localState, catalog, scan)
        },
        spotify,
        youtube,
    ) { device, spotifyState, youtubeState -> derive(device, spotifyState, youtubeState) }
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            derive(DeviceFacts(settings.value, tools.value, local.value, tracks.value, localScan.value), spotify.value, youtube.value),
        )

    fun recheck() = recheckTools()

    private fun derive(device: DeviceFacts, spotify: ProviderState, youtube: ProviderState) = sourceReadiness(
        SourceFacts(
            desktop = desktop,
            tools = device.tools,
            local = device.local,
            localTrackCount = device.tracks.size,
            localScan = device.scan,
            musicFolders = configuredMusicFolders(
                device.settings.localMusicFolders,
                device.settings.localMusicFoldersConfigured,
                defaultMusicFolder(),
            ),
            folderPicker = folderPicker,
            spotify = spotify,
            youtube = youtube,
        ),
    )

    private data class DeviceFacts(
        val settings: AppSettings,
        val tools: SourceTools,
        val local: ProviderState,
        val tracks: List<Track>,
        val scan: LocalScanResult?,
    )

    companion object {
        fun from(container: AppContainer, scope: CoroutineScope) = SourcesPresenter(
            settings = container.settings,
            tools = container.sourceTools,
            local = container.local.state,
            tracks = container.local.libraryTracks,
            localScan = container.local.scanResult,
            spotify = container.spotify.state,
            youtube = container.youtube.state,
            desktop = platformLabel() != "Android",
            folderPicker = supportsMusicFolderPicker(),
            defaultMusicFolder = ::defaultLocalMusicFolder,
            recheckTools = container::recheckSourceTools,
            scope = scope,
        )
    }
}
