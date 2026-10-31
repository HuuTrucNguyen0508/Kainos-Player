package com.universalmusic.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.universalmusic.player.app.RemoteControlState
import com.universalmusic.player.app.RemoteDeviceView
import com.universalmusic.player.data.sync.RemoteCommand
import com.universalmusic.player.data.sync.RemoteRepeat
import com.universalmusic.player.data.sync.RemoteStatus
import com.universalmusic.player.data.sync.positionAfter
import com.universalmusic.player.platform.currentTimeMillis
import com.universalmusic.player.ui.components.AccentToggle
import com.universalmusic.player.ui.components.LedgerRow
import com.universalmusic.player.ui.components.PlayButton
import com.universalmusic.player.ui.components.QuietSeekRail
import com.universalmusic.player.ui.components.SectionRule
import com.universalmusic.player.ui.components.SeekKind
import com.universalmusic.player.ui.components.SkinSlider
import com.universalmusic.player.ui.theme.AccentUse
import com.universalmusic.player.ui.theme.Accents
import com.universalmusic.player.ui.theme.LocalKainosPalette
import com.universalmusic.player.ui.theme.LocalSkinTokens
import kotlinx.coroutines.delay

/** "Playing on This PC ▾": which player Now Playing shows and controls, with a picker. */
@Composable
internal fun NowPlayingDeviceChip(
    remote: RemoteControlState,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val tokens = LocalSkinTokens.current
    val scheme = MaterialTheme.colorScheme
    val selected = remote.selected
    val name = selected?.name ?: remote.thisDeviceName
    Box(modifier) {
        Surface(
            onClick = { open = true },
            shape = tokens.rounding.full,
            color = if (selected != null) scheme.secondaryContainer else scheme.surfaceContainerHigh.copy(alpha = .72f),
            contentColor = if (selected != null) scheme.onSecondaryContainer else scheme.onSurface,
            modifier = Modifier
                .heightIn(min = 40.dp)
                .semantics { contentDescription = "Playing on $name. Choose a device" },
        ) {
            Row(
                Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(deviceIcon(selected == null, remote.thisIsDesktop), null, Modifier.size(18.dp))
                if (selected != null) LinkDot(selected.connected)
                Text("Playing on", style = MaterialTheme.typography.labelLarge)
                Text(
                    name,
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = tokens.display, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp),
                )
                Icon(Icons.Default.ArrowDropDown, null, Modifier.size(20.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DeviceMenuItem(
                name = remote.thisDeviceName,
                detail = "Plays and controls here",
                icon = deviceIcon(thisDevice = true, remote.thisIsDesktop),
                selected = selected == null,
                connected = null,
                onClick = { open = false; onSelect(null) },
            )
            remote.devices.forEach { device ->
                DeviceMenuItem(
                    name = device.name,
                    detail = deviceDetail(device),
                    icon = deviceIcon(thisDevice = false, remote.thisIsDesktop),
                    selected = device.deviceId == remote.selectedId,
                    connected = device.connected,
                    onClick = { open = false; onSelect(device.deviceId) },
                )
            }
        }
    }
}

@Composable
private fun DeviceMenuItem(
    name: String,
    detail: String,
    icon: ImageVector,
    selected: Boolean,
    connected: Boolean?,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Column {
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, null)
                connected?.let { LinkDot(it) }
            }
        },
        trailingIcon = if (selected) ({ Icon(Icons.Default.Check, contentDescription = "Selected") }) else null,
        onClick = onClick,
        modifier = Modifier.widthIn(min = 260.dp),
    )
}

private fun deviceDetail(device: RemoteDeviceView): String = when {
    !device.connected -> "Not reachable"
    !device.hasPlayer -> "Home sync only, no player"
    device.status == null -> "Connected, nothing playing"
    else -> listOfNotNull(device.status.title, device.status.artist).joinToString(" – ")
}

private fun deviceIcon(thisDevice: Boolean, thisIsDesktop: Boolean): ImageVector =
    if (thisDevice == thisIsDesktop) Icons.Default.Computer else Icons.Default.PhoneAndroid

/** Green while the link is live, gray when the device cannot be reached. */
@Composable
private fun LinkDot(connected: Boolean) {
    val palette = LocalKainosPalette.current
    Box(
        Modifier
            .size(8.dp)
            .background(palette.accent(if (connected) Accents.availableLocal else Accents.unavailable, AccentUse.Mark), LocalSkinTokens.current.rounding.full),
    )
}

/**
 * Another device's player, shown in place of this one's: cover, track, progress with seek,
 * transport, repeat, shuffle, volume, Play here and its queue. Controls are disabled while the
 * device is unreachable, so a press never queues up for later.
 */
@Composable
internal fun ColumnScope.RemoteNowPlaying(
    device: RemoteDeviceView,
    transferring: Boolean,
    artMax: androidx.compose.ui.unit.Dp,
    expand: Boolean,
    compact: Boolean,
    light: androidx.compose.ui.graphics.Color,
    onArtPositioned: (androidx.compose.ui.geometry.Rect) -> Unit,
    onSend: (RemoteCommand) -> Unit,
    onPlayHere: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val status = device.status
    val live = device.connected && device.hasPlayer
    val enabled = live && status != null && !transferring

    if (!live) {
        RemoteNotice(
            if (!device.connected) {
                "${device.name} isn't reachable. Open Kainos on it, or keep it playing, to control it from here."
            } else {
                "${device.name} is running Home sync without the player. Open Kainos on it to control it."
            },
        )
        Spacer(Modifier.height(12.dp))
    }

    NowPlayingArtwork(
        title = status?.title,
        artwork = device.artwork,
        artMax = artMax,
        expand = expand,
        compact = compact,
        light = light,
        onPositioned = onArtPositioned,
    )
    Spacer(Modifier.height(if (compact) 18.dp else 24.dp))
    Text(
        status?.title ?: "Nothing playing on ${device.name}",
        style = MaterialTheme.typography.headlineSmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        status?.artist ?: if (live) "Start something there, or pick a track here" else "Last seen state",
        style = MaterialTheme.typography.bodyLarge,
        color = scheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
    )
    Spacer(Modifier.height(12.dp))

    // The skin's play button and sliders do not grey out on their own; dim the whole block instead.
    Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else DISABLED_ALPHA)) {
        RemoteProgress(status, device.statusAtMs, ticking = live, enabled = enabled, onSeek = { onSend(RemoteCommand.SeekTo(it)) })
        Spacer(Modifier.height(8.dp))
        RemoteTransport(status, enabled, onSend)
        Spacer(Modifier.height(4.dp))
        RemoteVolume(status?.volume ?: 1f, enabled, onCommit = { onSend(RemoteCommand.SetVolume(it)) })
    }

    Spacer(Modifier.height(12.dp))
    FilledTonalButton(
        onClick = onPlayHere,
        enabled = enabled,
        shape = RoundedCornerShape(LocalSkinTokens.current.rounding.large),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) {
        Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (transferring) "Moving playback here…" else "Play here", style = MaterialTheme.typography.titleSmall)
    }
    Text(
        "Pauses ${device.name} and continues its queue on this device.",
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

/** The remote queue, below the first screen. Tap a row to play it there; the list icon makes it next. */
@Composable
internal fun RemoteQueue(device: RemoteDeviceView, enabled: Boolean, onSend: (RemoteCommand) -> Unit) {
    val upcoming = device.status?.queue?.drop(1).orEmpty()
    if (upcoming.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    Spacer(Modifier.height(16.dp))
    SectionRule("Up next on ${device.name}")
    upcoming.forEachIndexed { index, entry ->
        LedgerRow(onClick = if (enabled) ({ onSend(RemoteCommand.PlayItem(entry.itemId)) }) else null) {
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                entry.artist?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            entry.durationMs?.let {
                Text(formatTime(it), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }
            if (index > 0) {
                IconButton(onClick = { onSend(RemoteCommand.PlayNext(entry.itemId)) }, enabled = enabled) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = "Play ${entry.title} next")
                }
            } else {
                Spacer(Modifier.width(48.dp))
            }
        }
    }
}

/** A short-lived problem from the last remote action, such as "Poco didn't respond". */
@Composable
internal fun RemoteMessageLine(text: String, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(LocalSkinTokens.current.rounding.normal),
        color = scheme.errorContainer,
        contentColor = scheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Dismiss") }
        }
    }
    LaunchedEffect(text) {
        delay(6_000)
        onDismiss()
    }
}

@Composable
private fun RemoteNotice(text: String) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(LocalSkinTokens.current.rounding.normal),
        color = scheme.surfaceContainerHighest,
        contentColor = scheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.LinkOff, null, tint = scheme.onSurfaceVariant)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RemoteProgress(status: RemoteStatus?, statusAtMs: Long, ticking: Boolean, enabled: Boolean, onSeek: (Long) -> Unit) {
    var nowMs by remember { mutableLongStateOf(currentTimeMillis()) }
    // An unreachable device's last position stays put rather than running on as if it were heard.
    LaunchedEffect(status, statusAtMs, ticking) {
        nowMs = if (ticking) currentTimeMillis() else statusAtMs
        while (ticking && status?.isPlaying == true) {
            delay(500)
            nowMs = currentTimeMillis()
        }
    }
    var scrub by remember(status?.currentItemId) { mutableStateOf<Float?>(null) }
    // Holds a sent seek until the device's next status confirms or replaces it.
    var sent by remember(status, statusAtMs) { mutableStateOf<Long?>(null) }
    val duration = status?.durationMs?.takeIf { it > 0 }
    val position = sent ?: status?.positionAfter(nowMs - statusAtMs) ?: 0L
    if (duration == null) {
        Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) { QuietSeekRail() }
    } else {
        SkinSlider(
            durationMs = duration,
            isPlaying = status.isPlaying,
            value = scrub ?: playbackProgress(position, duration),
            onValueChange = { scrub = it },
            onValueChangeFinished = {
                scrub?.let { fraction ->
                    val target = (fraction * duration).toLong()
                    sent = target
                    onSeek(target)
                }
                scrub = null
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    val timeStyle = MaterialTheme.typography.titleSmall.copy(fontFamily = LocalSkinTokens.current.display)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(displayedPositionMs(scrub, duration, position)), style = timeStyle)
        Text(duration?.let(::formatTime) ?: "", style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RemoteTransport(status: RemoteStatus?, enabled: Boolean, onSend: (RemoteCommand) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        AccentToggle(
            checked = status?.shuffle == true,
            onClick = { onSend(RemoteCommand.SetShuffle(status?.shuffle != true)) },
            slot = Accents.shuffle,
            icon = Icons.Default.Shuffle,
            contentDescription = if (status?.shuffle == true) "Turn shuffle off" else "Turn shuffle on",
            enabled = enabled,
        )
        IconButton(onClick = { onSend(RemoteCommand.Previous) }, enabled = enabled && status.canSkipPrevious, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp))
        }
        PlayButton(
            playing = status?.isPlaying == true || status?.buffering == true,
            onClick = { onSend(if (status?.isPlaying == true || status?.buffering == true) RemoteCommand.Pause else RemoteCommand.Play) },
            enabled = enabled,
        )
        IconButton(onClick = { onSend(RemoteCommand.Next) }, enabled = enabled && status.canSkipNext, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp))
        }
        val repeat = status?.repeat ?: RemoteRepeat.OFF
        AccentToggle(
            checked = repeat != RemoteRepeat.OFF,
            onClick = { onSend(RemoteCommand.SetRepeat(repeat.next())) },
            slot = Accents.repeat,
            icon = if (repeat == RemoteRepeat.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
            contentDescription = "Repeat ${repeat.name.lowercase()}. Change repeat mode",
            enabled = enabled,
        )
    }
}

private val RemoteStatus?.canSkipPrevious get() = this?.canSkipPrevious == true
private val RemoteStatus?.canSkipNext get() = this?.canSkipNext == true

private fun RemoteRepeat.next(): RemoteRepeat = when (this) {
    RemoteRepeat.OFF -> RemoteRepeat.ALL
    RemoteRepeat.ALL -> RemoteRepeat.ONE
    RemoteRepeat.ONE -> RemoteRepeat.OFF
}

@Composable
private fun RemoteVolume(volume: Float, enabled: Boolean, onCommit: (Float) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    var sent by remember(volume) { mutableStateOf<Float?>(null) }
    val shown = drag ?: sent ?: volume
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = { (if (shown > 0.001f) 0f else 1f).let { sent = it; onCommit(it) } }, enabled = enabled) {
            Icon(
                when (volumeIconKind(shown)) {
                    VolumeIconKind.MUTE -> Icons.AutoMirrored.Filled.VolumeMute
                    VolumeIconKind.DOWN -> Icons.AutoMirrored.Filled.VolumeDown
                    VolumeIconKind.UP -> Icons.AutoMirrored.Filled.VolumeUp
                },
                contentDescription = if (volumeIconKind(shown) == VolumeIconKind.MUTE) "Unmute" else "Mute",
            )
        }
        SkinSlider(
            kind = SeekKind.Level,
            value = shown,
            onValueChange = { drag = it },
            onValueChangeFinished = {
                drag?.let {
                    sent = it
                    onCommit(it)
                }
                drag = null
            },
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Text(
            volumePercentLabel(shown),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 36.dp),
        )
    }
}

private const val DISABLED_ALPHA = 0.45f
