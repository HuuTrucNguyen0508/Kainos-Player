package com.universalmusic.player.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.universalmusic.player.domain.echo.EchoDevice
import com.universalmusic.player.domain.echo.EchoPlayState
import com.universalmusic.player.domain.echo.EchoStatus
import com.universalmusic.player.domain.echo.EchoTrack
import com.universalmusic.player.domain.echo.shownEcho
import com.universalmusic.player.domain.model.Artwork
import com.universalmusic.player.platform.currentTimeMillis
import com.universalmusic.player.ui.components.ArtworkImage
import com.universalmusic.player.ui.components.SeekKind
import com.universalmusic.player.ui.components.SeekTrack
import com.universalmusic.player.ui.components.Slip
import com.universalmusic.player.ui.components.albumLightStrip
import com.universalmusic.player.ui.components.rememberAlbumLight
import com.universalmusic.player.ui.theme.LocalKainosPalette
import com.universalmusic.player.ui.theme.LocalSkinTokens
import kotlinx.coroutines.delay

/** Up-next rows the card lists; the gateway may send more. */
internal const val ECHO_UP_NEXT_ROWS = 3

/** Card width at which up next moves beside the song instead of under it. */
private val EchoSideBySide = 640.dp

private data class ShownEcho(val status: EchoStatus, val device: EchoDevice)

/**
 * What the Echo is playing, read-only: Alexa accepts commands only by voice, so the card has no
 * controls and takes no focus. It grows in when an Echo starts and folds away when it goes quiet.
 */
@Composable
internal fun EchoOnHome(status: EchoStatus?, modifier: Modifier = Modifier) {
    val device = remember(status) { status?.shownEcho(currentTimeMillis()) }
    // Keep the last card while it folds away, so the exit animation has something to show.
    var shown by remember { mutableStateOf<ShownEcho?>(null) }
    if (status != null && device != null) shown = ShownEcho(status, device)
    AnimatedVisibility(
        visible = device != null,
        modifier = modifier,
        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
    ) {
        shown?.let { EchoCard(it.status, it.device, Modifier.padding(top = 16.dp)) }
    }
}

@Composable
private fun EchoCard(status: EchoStatus, device: EchoDevice, modifier: Modifier = Modifier) {
    val track = device.track ?: return
    val palette = LocalKainosPalette.current
    val light by rememberAlbumLight(track.artUrl)
    val playing = device.state == EchoPlayState.PLAYING
    // Ticks only while playing; a paused position does not move.
    val localNow by produceState(currentTimeMillis(), status, playing) {
        value = currentTimeMillis()
        while (playing) {
            delay(1_000 - value % 1_000)
            value = currentTimeMillis()
        }
    }
    val positionMs = device.positionAt(status.gatewayNow(localNow))
    val upNext = device.upNext.take(ECHO_UP_NEXT_ROWS)

    Slip(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        BoxWithConstraints(Modifier.fillMaxWidth().albumLightStrip(light, palette.isDark)) {
            val sideBySide = maxWidth >= EchoSideBySide && upNext.isNotEmpty()
            val song: @Composable (Modifier) -> Unit = { m ->
                EchoSong(track, device, playing, positionMs, artSize = if (sideBySide) 96.dp else 72.dp, m)
            }
            if (sideBySide) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(16.dp)) {
                    song(Modifier.weight(1.4f))
                    Spacer(Modifier.width(20.dp))
                    Box(Modifier.width(1.dp).fillMaxHeight().background(palette.hair))
                    Spacer(Modifier.width(20.dp))
                    EchoUpNext(upNext, Modifier.weight(1f))
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    song(Modifier.fillMaxWidth())
                    if (upNext.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.hair))
                        Spacer(Modifier.height(10.dp))
                        EchoUpNext(upNext, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun EchoSong(
    track: EchoTrack,
    device: EchoDevice,
    playing: Boolean,
    positionMs: Long,
    artSize: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalSkinTokens.current
    val scheme = MaterialTheme.colorScheme
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ArtworkImage(track.artUrl?.let(::Artwork), track.title, Modifier.size(artSize), track.title)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (playing) Icons.Default.Speaker else Icons.Default.Pause,
                    contentDescription = null,
                    tint = if (playing) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (playing) "Playing on Echo" else "Paused on Echo",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (playing) scheme.primary else scheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                PlayModeMarks(device)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                track.title,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = tokens.display),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            track.artist?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            EchoProgress(positionMs, track.durationMs, playing)
        }
    }
}

@Composable
private fun PlayModeMarks(device: EchoDevice) {
    val marks = buildList<Pair<ImageVector, String>> {
        if (device.shuffle) add(Icons.Default.Shuffle to "Shuffle on")
        when {
            device.repeatOne -> add(Icons.Default.RepeatOne to "Repeating this song")
            device.loop -> add(Icons.Default.Repeat to "Repeating the queue")
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        marks.forEach { (icon, label) ->
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun EchoProgress(positionMs: Long, durationMs: Long?, playing: Boolean) {
    val fraction = durationMs?.let { (positionMs.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
    val timeStyle = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
    val timeColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (durationMs != null) {
            Text(formatTime(positionMs), style = timeStyle, color = timeColor)
            Spacer(Modifier.width(10.dp))
        }
        SeekTrack(fraction, durationMs, isPlaying = playing, modifier = Modifier.weight(1f), kind = SeekKind.Compact)
        if (durationMs != null) {
            Spacer(Modifier.width(10.dp))
            Text(formatTime(durationMs), style = timeStyle, color = timeColor)
        }
    }
}

@Composable
private fun EchoUpNext(upNext: List<EchoTrack>, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Up next", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        upNext.forEach { next ->
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    next.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                next.artist?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
        // Shown in both states so pausing never changes the card's height.
        Text(
            "Say “Alexa, suivant” to skip",
            style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
