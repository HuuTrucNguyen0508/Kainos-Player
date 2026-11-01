package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.universalmusic.player.ui.components.LedgerRow
import com.universalmusic.player.ui.components.SectionRule
import com.universalmusic.player.ui.components.Slip
import com.universalmusic.player.ui.components.albumLightStrip
import com.universalmusic.player.ui.components.rememberAlbumLight
import com.universalmusic.player.ui.theme.LocalKainosPalette
import com.universalmusic.player.ui.theme.LocalSkinTokens

/**
 * Home before anything has played. One question, the easiest answer set large, the services beside
 * it. Nothing here blocks the tabs, and it leaves for good once music exists.
 */
@Composable
internal fun FirstRun(
    sources: List<SourceReadiness>,
    onAct: (SourceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lead = sources.firstOrNull { it.status == SourceStatus.Ready } ?: sources.first()
    val others = sources - lead
    val canPlay = lead.status == SourceStatus.Ready
    val display = LocalSkinTokens.current.display
    BoxWithConstraints(modifier.widthIn(max = 1040.dp).fillMaxWidth()) {
        val wide = maxWidth >= 720.dp
        Column {
            Text(
                if (canPlay) "Ready when you are" else "Where does your music live?",
                style = (if (wide) MaterialTheme.typography.displayLarge else MaterialTheme.typography.displaySmall)
                    .copy(fontFamily = display),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (canPlay) "${lead.source.title} is set up. Pick something to play." else "Start with one. You can add the others any time.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(if (wide) 32.dp else 24.dp))
            val services: @Composable (Modifier) -> Unit = { columnModifier ->
                Column(columnModifier) {
                    SectionRule(if (canPlay) "Add another source" else "Or connect a service")
                    others.forEach { SourceRow(it, onAct) }
                }
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    LeadSource(lead, onAct, Modifier.weight(1f))
                    services(Modifier.weight(1f))
                }
            } else {
                LeadSource(lead, onAct, Modifier.fillMaxWidth())
                Spacer(Modifier.height(20.dp))
                services(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun LeadSource(source: SourceReadiness, onAct: (SourceAction) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalKainosPalette.current
    val light by rememberAlbumLight(null)
    Slip(modifier) {
        Column(Modifier.albumLightStrip(light, palette.isDark).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SourceTile(source.source, size = 44.dp)
                Column {
                    Text(source.source.title, style = MaterialTheme.typography.titleLarge.copy(fontFamily = LocalSkinTokens.current.display))
                    SourceStatusMark(source, on = palette.sheet)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(source.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            source.step?.let { step ->
                Spacer(Modifier.height(16.dp))
                Button(onClick = { onAct(step.action) }) { Text(step.label) }
            }
        }
    }
}

@Composable
private fun SourceRow(source: SourceReadiness, onAct: (SourceAction) -> Unit) {
    val step = source.step
    LedgerRow(onClick = step?.let { { onAct(it.action) } }) {
        SourceTile(source.source)
        Column(Modifier.weight(1f).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(source.source.title, style = MaterialTheme.typography.titleSmall)
                SourceStatusMark(source)
            }
            Text(source.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            step?.let {
                Text(it.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** The last step to first sound: music is here, nothing has played yet. */
@Composable
internal fun FirstSoundCard(
    local: SourceReadiness,
    onPlay: () -> Unit,
    onAct: (SourceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalKainosPalette.current
    val light by rememberAlbumLight(null)
    val ready = local.status == SourceStatus.Ready
    Slip(modifier.fillMaxWidth()) {
        Row(
            Modifier.albumLightStrip(light, palette.isDark).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SourceTile(local.source, size = 44.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    if (ready) "Your music is ready" else local.statusLabel,
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = LocalSkinTokens.current.display),
                )
                Text(local.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (ready) {
                Button(onClick = onPlay) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Play")
                }
            } else {
                local.step?.let { step -> Button(onClick = { onAct(step.action) }) { Text(step.label) } }
            }
        }
    }
}
