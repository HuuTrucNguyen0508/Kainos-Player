package com.universalmusic.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.universalmusic.player.ui.theme.AccentSlot
import com.universalmusic.player.ui.theme.AccentUse
import com.universalmusic.player.ui.theme.Accents
import com.universalmusic.player.ui.theme.LocalKainosPalette

private val SourceStatus.slot: AccentSlot
    get() = when (this) {
        SourceStatus.Ready -> Accents.availableLocal
        SourceStatus.Checking -> Accents.downloading
        is SourceStatus.NeedsSetup -> Accents.waiting
        SourceStatus.Trouble -> Accents.failed
    }

/** Ready or not, in words beside an icon: the hue only reinforces them. */
@Composable
internal fun SourceStatusMark(
    source: SourceReadiness,
    modifier: Modifier = Modifier,
    on: Color = LocalKainosPalette.current.ground,
) {
    val tint = LocalKainosPalette.current.accent(source.status.slot, AccentUse.Mark, on)
    val iconSize = Modifier.size(14.dp)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        when (source.status) {
            SourceStatus.Ready -> Icon(Icons.Default.CheckCircle, null, iconSize, tint = tint)
            SourceStatus.Checking -> CircularProgressIndicator(iconSize, color = tint, strokeWidth = 2.dp)
            is SourceStatus.NeedsSetup -> Icon(Icons.Default.RadioButtonUnchecked, null, iconSize, tint = tint)
            SourceStatus.Trouble -> Icon(Icons.Default.ErrorOutline, null, iconSize, tint = tint)
        }
        Text(source.statusLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
internal fun SourceTile(source: MusicSource, size: Dp = 36.dp) {
    val palette = LocalKainosPalette.current
    val fill = palette.accentContainer(source.kind.slot)
    Box(Modifier.size(size).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
        Icon(source.kind.icon, null, Modifier.size(size / 2), tint = palette.accent(source.kind.slot, AccentUse.Mark, fill))
    }
}
