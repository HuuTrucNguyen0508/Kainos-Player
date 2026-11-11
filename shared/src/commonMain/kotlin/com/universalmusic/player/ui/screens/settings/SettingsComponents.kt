package com.universalmusic.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import com.universalmusic.player.ui.components.LedgerRow
import com.universalmusic.player.ui.theme.AccentSlot
import com.universalmusic.player.ui.theme.AccentUse
import com.universalmusic.player.ui.theme.Accents
import com.universalmusic.player.ui.theme.LocalKainosPalette
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universalmusic.player.domain.model.SourceSelectionMode
import com.universalmusic.player.platform.SpotifyWebPlaybackFailure
import com.universalmusic.player.platform.describeLibrespotConnectionFailure

@Composable
internal fun SettingToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    LedgerRow(modifier = Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange)) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** The spot Settings was opened at, if any. */
internal val LocalSettingsArrival = staticCompositionLocalOf<SettingsTarget?> { null }

/** A named place in Settings: scrolled to and marked when a setup action lands here, with [focus] taken. */
@Composable
internal fun SettingsSpot(
    target: SettingsTarget,
    modifier: Modifier = Modifier,
    focus: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val arrival = LocalSettingsArrival.current
    val arrived = arrival == target
    val requester = remember { BringIntoViewRequester() }
    val mark = MaterialTheme.colorScheme.primary
    LaunchedEffect(arrival) {
        if (!arrived) return@LaunchedEffect
        withFrameNanos { }
        requester.bringIntoView()
        focus?.let { runCatching { it.requestFocus() } }
    }
    Column(
        modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .testTag("settings-spot-${target.name}")
            .then(
                if (arrived) {
                    Modifier
                        .drawBehind { drawLine(mark, Offset(1.dp.toPx(), 0f), Offset(1.dp.toPx(), size.height), 2.dp.toPx()) }
                        .padding(start = 12.dp)
                } else {
                    Modifier
                },
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** One numbered step of a provider setup. These do run in order; [done] swaps the number for a check. */
@Composable
internal fun SetupStep(
    number: Int,
    title: String,
    done: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val palette = LocalKainosPalette.current
    val fill = if (done) palette.accentContainer(Accents.availableLocal) else MaterialTheme.colorScheme.surfaceContainerHighest
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
            if (done) {
                Icon(Icons.Default.Check, "Done", Modifier.size(16.dp), tint = palette.accent(Accents.availableLocal, AccentUse.Mark, fill))
            } else {
                Text("$number", style = MaterialTheme.typography.labelMedium)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
internal fun SetupNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal fun spotifyPlaybackFailureMessage(failure: SpotifyWebPlaybackFailure): String = when (failure) {
    SpotifyWebPlaybackFailure.LibrespotNotFound -> "Install librespot with scripts/install-librespot.sh, then try setup again."
    SpotifyWebPlaybackFailure.LibrespotAuthenticationRequired ->
        "Set up in-app Spotify playback to sign in to the receiver."
    is SpotifyWebPlaybackFailure.LibrespotExited -> {
        val detail = failure.detail.orEmpty()
        describeLibrespotConnectionFailure(detail)?.let { return it }
        when {
            detail.contains("GeneratedMessageV3", ignoreCase = true) ||
                detail.contains("protobuf", ignoreCase = true) ->
                "Spotify setup failed: a required library is missing from this build. Reinstall the app."
            detail.isNotBlank() -> detail
            else -> "The Spotify receiver stopped. Try setup again."
        }
    }
    is SpotifyWebPlaybackFailure.Message -> failure.detail
    else -> "Spotify receiver setup failed. Try setup again."
}

internal enum class SettingsSection(val label: String, val accent: AccentSlot) {
    Playback("Playback", AccentSlot.Orange),
    Library("Library", AccentSlot.Purple),
    Appearance("Appearance", AccentSlot.Pink),
    Sources("Music sources", AccentSlot.Green),
    Devices("Devices and sync", AccentSlot.Blue),
    Downloads("Downloads", AccentSlot.Teal),
    Backup("Backup", AccentSlot.Yellow),
    Advanced("Advanced", AccentSlot.Gray),
}

internal fun SourceSelectionMode.label(): String = when (this) {
    SourceSelectionMode.AUTOMATIC -> "Automatic — Best available"
    SourceSelectionMode.PREFER_LOSSLESS -> "Prefer lossless"
    SourceSelectionMode.PREFER_HIGHEST_BITRATE -> "Prefer highest bitrate"
    SourceSelectionMode.PREFER_SPOTIFY -> "Prefer Spotify"
    SourceSelectionMode.PREFER_YOUTUBE_MUSIC -> "Prefer YouTube Music"
    SourceSelectionMode.FORCE_SPOTIFY -> "Spotify only"
    SourceSelectionMode.FORCE_YOUTUBE_MUSIC -> "YouTube Music only"
}
