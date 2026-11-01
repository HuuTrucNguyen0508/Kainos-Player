package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.platform.SpotifyWebPlaybackState
import com.universalmusic.player.platform.openUrl
import com.universalmusic.player.ui.components.SectionRule
import com.universalmusic.player.ui.reportsTextInputFocus
import com.universalmusic.player.ui.theme.Accents

internal const val SpotifyDashboardUrl = "https://developer.spotify.com/dashboard"
internal const val YouTubeApiUrl = "https://console.cloud.google.com/apis/library/youtube.googleapis.com"
internal const val GoogleCredentialsUrl = "https://console.cloud.google.com/apis/credentials"

@Composable
internal fun SettingsSpotifySection(
    presenter: SettingsConnectPresenter,
    readiness: SourceReadiness,
    onRecheck: () -> Unit,
    openLink: (String) -> Unit = ::openUrl,
) {
    val ui by presenter.state.collectAsState()
    val settings = ui.settings
    val ready = ui.ready
    val busy = ui.action.busy
    val spotify = ui.spotify
    val configured = ui.config.hasSpotifyCredentials
    val connected = spotify == ProviderState.AVAILABLE || spotify == ProviderState.RATE_LIMITED
    val needs = readiness.needs
    var clientId by remember(settings.spotifyClientId, ready) { mutableStateOf(ui.config.spotifyClientId.orEmpty()) }
    val firstMove = remember { FocusRequester() }

    SectionRule(MusicSource.Spotify.title, accent = Accents.spotify, trailing = { SourceStatusMark(readiness) })
    if (!connected) {
        SetupNote(
            "Spotify only lets an app in with a Client ID, and Kainos has no shared one. " +
                "You make your own for free in a few minutes. It stays on this device.",
        )
    }
    SettingsSpot(SettingsTarget.SpotifyClientId, focus = firstMove) {
        if (!connected) {
            SetupStep(1, "Create a Spotify developer app", done = configured) {
                if (!configured) {
                    SetupNote("Sign in to the Spotify developer dashboard and choose Create app. Any name works. Select Web API when it asks.")
                    OutlinedButton(onClick = { openLink(SpotifyDashboardUrl) }, modifier = Modifier.focusRequester(firstMove)) {
                        Text("Open Spotify dashboard")
                    }
                }
            }
            SetupStep(2, "Add this redirect URI to the app") {
                CopyableValue(ui.config.spotifyRedirectUri)
            }
        }
        SetupStep(3, "Paste the Client ID here", done = configured) {
            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it },
                label = { Text("Spotify Client ID") },
                singleLine = true,
                enabled = ready && !busy,
                modifier = Modifier.fillMaxWidth().reportsTextInputFocus(),
            )
            Button(enabled = ready && !busy, onClick = { presenter.saveSpotifyClientId(clientId) }) {
                Text(if (busy) "Working…" else "Save Client ID")
            }
            ActionResult(ui.action, SettingsTarget.SpotifyClientId)
        }
    }
    val connectFocus = remember { FocusRequester() }
    SettingsSpot(SettingsTarget.SpotifySignIn, focus = connectFocus) {
        SetupStep(4, "Sign in to Spotify", done = connected) {
            if (!connected) SetupNote("Spotify's own sign-in page opens. Your liked songs and playlists then appear in Library.")
            if (connected) {
                OutlinedButton(onClick = presenter::disconnect, enabled = !busy && ready) { Text("Disconnect") }
            } else {
                Button(
                    onClick = presenter::connect,
                    enabled = configured && !busy && ready,
                    modifier = Modifier.focusRequester(connectFocus),
                ) { Text("Connect") }
            }
            ActionResult(ui.action, SettingsTarget.SpotifySignIn)
        }
    }
    if (presenter.nativeSpotify) {
        val playbackFocus = remember { FocusRequester() }
        SettingsSpot(SettingsTarget.SpotifyPlayback, focus = playbackFocus) {
            val receiverDone = needs.none { it == SourceNeed.SpotifyReceiverInstall || it == SourceNeed.SpotifyReceiverSignIn }
            SetupStep(5, "Set up playback", done = receiverDone) {
                if (SourceNeed.SpotifyReceiverInstall in needs) {
                    SetupNote(
                        "Kainos plays Spotify through librespot, a separate program. Run scripts/install-librespot.sh " +
                            "from the Kainos source, or put librespot on your PATH.",
                    )
                    OutlinedButton(onClick = onRecheck, modifier = Modifier.focusRequester(playbackFocus)) { Text("Check again") }
                } else {
                    if (!receiverDone) {
                        SetupNote("One more Spotify sign-in lets Kainos play audio itself, in the background. Playback needs Spotify Premium. You only do this once.")
                    }
                    OutlinedButton(
                        enabled = ready && !busy,
                        onClick = presenter::setupReceiver,
                        modifier = Modifier.focusRequester(playbackFocus),
                    ) {
                        Text("Set up in-app Spotify playback")
                    }
                }
                val failure = (ui.playback as? SpotifyWebPlaybackState.Failed)?.reason
                if (failure != null) {
                    Text(spotifyPlaybackFailureMessage(failure), color = MaterialTheme.colorScheme.error)
                }
                ActionResult(ui.action, SettingsTarget.SpotifyPlayback)
            }
        }
    }
    if (connected) {
        var discoverPlaylistInput by remember(settings.spotifyDiscoverWeeklyPlaylistId, ready) {
            mutableStateOf(settings.spotifyDiscoverWeeklyPlaylistId.orEmpty())
        }
        OutlinedTextField(
            value = discoverPlaylistInput,
            onValueChange = { discoverPlaylistInput = it },
            modifier = Modifier.fillMaxWidth().reportsTextInputFocus(),
            singleLine = true,
            label = { Text("Discover Weekly playlist link") },
            placeholder = { Text("Paste Spotify share URL or playlist id") },
        )
        OutlinedButton(enabled = !busy, onClick = { presenter.saveDiscoverWeekly(discoverPlaylistInput) }) {
            Text("Save Discover Weekly link")
        }
        SetupNote(
            "Spotify’s Web API no longer lists Discover Weekly for most developer apps. " +
                "In Spotify: open Discover Weekly → Share → Copy link, paste it here, then save. " +
                "Or heart Discover Weekly in Spotify so it appears in your library, then refresh.",
        )
        OutlinedButton(enabled = !ui.libraryLoading && !busy, onClick = presenter::refreshLibrary) {
            Text(if (ui.libraryLoading) "Loading library…" else "Refresh Spotify library")
        }
        SetupNote("If Spotify reports a rate limit, wait for its retry time before refreshing again.")
        ActionResult(ui.action, spot = null)
    }
    ui.libraryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
internal fun SettingsYouTubeSection(
    presenter: SettingsConnectPresenter,
    readiness: SourceReadiness,
    onRecheck: () -> Unit,
    openLink: (String) -> Unit = ::openUrl,
) {
    val ui by presenter.state.collectAsState()
    val ready = ui.ready
    val busy = ui.action.busy
    val configured = ui.config.hasYouTubeCredentials
    val needs = readiness.needs
    var apiKey by remember(ui.settings.youtubeDataApiKey, ready) { mutableStateOf(ui.config.youtubeDataApiKey.orEmpty()) }
    val firstMove = remember { FocusRequester() }

    SectionRule(MusicSource.YouTube.title, accent = Accents.youtube, trailing = { SourceStatusMark(readiness) })
    if (!configured) {
        SetupNote(
            "Google only answers searches from an app with an API key, and Kainos has no shared one. " +
                "You make your own for free. It stays on this device.",
        )
    }
    SettingsSpot(SettingsTarget.YouTubeKey, focus = firstMove) {
        if (!configured) {
            SetupStep(1, "Turn on YouTube Data API v3") {
                SetupNote("In Google Cloud, create a project if you have none, then choose Enable.")
                OutlinedButton(onClick = { openLink(YouTubeApiUrl) }, modifier = Modifier.focusRequester(firstMove)) {
                    Text("Open Google Cloud")
                }
            }
            SetupStep(2, "Create an API key") {
                SetupNote("Choose Create credentials, then API key, and copy the key.")
                OutlinedButton(onClick = { openLink(GoogleCredentialsUrl) }) { Text("Open credentials page") }
            }
        }
        SetupStep(3, "Paste the key here", done = configured) {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("YouTube Data API key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = ready && !busy,
                modifier = Modifier.fillMaxWidth().reportsTextInputFocus(),
            )
            Button(enabled = ready && !busy, onClick = { presenter.saveYouTubeKey(apiKey) }) {
                Text(if (busy) "Working…" else "Save API key")
            }
            ActionResult(ui.action, SettingsTarget.YouTubeKey)
        }
    }
    if (SourceNeed.YouTubeExtractor in needs) {
        val recheckFocus = remember { FocusRequester() }
        SettingsSpot(SettingsTarget.YouTubePlayback, focus = recheckFocus) {
            SetupStep(4, "Install yt-dlp") {
                SetupNote(
                    "Kainos gets YouTube audio through yt-dlp. Install it with your package manager, " +
                        "or run scripts/install-yt-dlp.sh from the Kainos source.",
                )
                OutlinedButton(onClick = onRecheck, modifier = Modifier.focusRequester(recheckFocus)) { Text("Check again") }
            }
        }
    }
}

@Composable
private fun ActionResult(action: ProviderActionState, spot: SettingsTarget?) {
    if (action.spot != spot) return
    action.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    action.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Suppress("DEPRECATION") // LocalClipboard needs a platform ClipEntry; this is the common text path.
@Composable
private fun CopyableValue(value: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(value) { mutableStateOf(false) }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
        TextButton(onClick = {
            clipboard.setText(AnnotatedString(value))
            copied = true
        }) { Text(if (copied) "Copied" else "Copy") }
    }
}
