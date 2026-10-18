package com.universalmusic.player.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.universalmusic.player.data.cache.TrackAvailability
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.ui.theme.AccentSlot
import com.universalmusic.player.ui.theme.AccentUse
import com.universalmusic.player.ui.theme.Accents
import com.universalmusic.player.ui.theme.GroundStyle
import com.universalmusic.player.ui.theme.LocalKainosPalette
import com.universalmusic.player.ui.theme.LocalSkinTokens
import com.universalmusic.player.ui.theme.Skin
import com.universalmusic.player.ui.theme.contrastingColor

/** Folio's dotted almanac paper, or Caelestia's plain background. */
@Composable
fun Modifier.skinGround(): Modifier {
    val palette = LocalKainosPalette.current
    if (LocalSkinTokens.current.ground == GroundStyle.PLAIN) return background(palette.ground)
    val dot = lerp(palette.ground, palette.rule, if (palette.isDark) .8f else 1f)
    return drawWithCache {
        val spacing = 16.dp.toPx()
        val width = size.width
        val height = size.height
        val points = buildList {
            var y = spacing / 2
            while (y < height) {
                var x = spacing / 2
                while (x < width) { add(Offset(x, y)); x += spacing }
                y += spacing
            }
        }
        val radius = 1.6.dp.toPx()
        onDrawBehind {
            drawRect(palette.ground)
            drawPoints(points, PointMode.Points, dot, radius, StrokeCap.Round)
        }
    }
}

enum class SourceKind(val slot: AccentSlot, val icon: ImageVector) {
    Spotify(Accents.spotify, Icons.Default.GraphicEq),
    YouTube(Accents.youtube, Icons.Default.PlayCircle),
    Local(Accents.local, Icons.Default.SdStorage),
    Kainos(Accents.playlist, Icons.AutoMirrored.Filled.QueueMusic),
    Folder(Accents.folder, Icons.Default.Folder),
    ;

    companion object {
        fun of(provider: ProviderId): SourceKind = when (provider) {
            ProviderId.SPOTIFY -> Spotify
            ProviderId.YOUTUBE_MUSIC -> YouTube
            ProviderId.LOCAL -> Local
            ProviderId.SAMPLE -> Kainos
        }
    }
}

/**
 * Where something comes from: a short coloured mark plus its label. Folio draws a dash the way the
 * launcher marks its tiles; Caelestia a dot. The label carries the meaning, the hue only echoes it.
 */
@Composable
fun SourceMark(
    kind: SourceKind,
    label: String,
    modifier: Modifier = Modifier,
    on: Color = LocalKainosPalette.current.ground,
    showIcon: Boolean = false,
) {
    val palette = LocalKainosPalette.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showIcon) {
            Icon(kind.icon, null, Modifier.size(14.dp), tint = palette.accent(kind.slot, AccentUse.Mark, on))
        } else {
            AccentTick(palette.accent(kind.slot, AccentUse.Mark, on))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AccentTick(color: Color) {
    if (LocalSkinTokens.current.skin == Skin.FOLIO) {
        Box(Modifier.size(10.dp, 2.dp).background(color))
    } else {
        Box(Modifier.size(7.dp).clip(LocalSkinTokens.current.rounding.full).background(color))
    }
}

val TrackAvailability.slot: AccentSlot
    get() = when (this) {
        TrackAvailability.AVAILABLE_LOCALLY -> Accents.availableLocal
        TrackAvailability.CACHED -> Accents.cached
        TrackAvailability.DOWNLOADING -> Accents.downloading
        TrackAvailability.WAITING_FOR_NETWORK -> Accents.waiting
        TrackAvailability.UNAVAILABLE -> Accents.unavailable
        TrackAvailability.FAILED -> Accents.failed
    }

private val TrackAvailability.icon: ImageVector
    get() = when (this) {
        TrackAvailability.AVAILABLE_LOCALLY -> Icons.Default.SdStorage
        TrackAvailability.CACHED -> Icons.Default.OfflinePin
        TrackAvailability.DOWNLOADING -> Icons.Default.Downloading
        TrackAvailability.WAITING_FOR_NETWORK -> Icons.Default.HourglassEmpty
        TrackAvailability.UNAVAILABLE -> Icons.Default.CloudOff
        TrackAvailability.FAILED -> Icons.Default.ErrorOutline
    }

/** Offline state: icon and label always, hue as reinforcement only. */
@Composable
fun AvailabilityMark(
    status: TrackAvailability,
    label: String,
    modifier: Modifier = Modifier,
    on: Color = LocalKainosPalette.current.ground,
) {
    val palette = LocalKainosPalette.current
    val mark = palette.accent(status.slot, AccentUse.Mark, on)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(status.icon, null, Modifier.size(14.dp), tint = mark)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (status == TrackAvailability.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The launcher's icon tile: art inside a rounded tile with a short coloured underline, the title
 * and a source line beneath. Caelestia shows the same parts as a tonal card with larger art.
 */
@Composable
fun IconTile(
    title: String,
    subtitle: String?,
    accent: AccentSlot,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = if (LocalSkinTokens.current.skin == Skin.FOLIO) 104.dp else 136.dp,
    art: @Composable (Modifier) -> Unit,
) {
    val palette = LocalKainosPalette.current
    val tokens = LocalSkinTokens.current
    val folio = tokens.skin == Skin.FOLIO
    val tileColor = if (folio) palette.tile else MaterialTheme.colorScheme.surfaceContainer
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(if (folio) tokens.rounding.large else tokens.rounding.extraLarge)
    Column(
        modifier.width(width).clip(shape).clickable(onClick = onClick).then(
            if (folio) Modifier else Modifier.background(tileColor),
        ),
        horizontalAlignment = if (folio) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        if (folio) {
            Column(
                Modifier.fillMaxWidth().clip(shape).background(tileColor).border(1.dp, palette.hair, shape).padding(top = 12.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                art(Modifier.size(width * .56f))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.size(16.dp, 2.dp).background(palette.accent(accent, AccentUse.Mark, tileColor)))
            }
            Spacer(Modifier.height(6.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Column(Modifier.padding(8.dp)) {
                art(Modifier.fillMaxWidth().height(width - 16.dp))
                Spacer(Modifier.height(8.dp))
                Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.size(6.dp).clip(tokens.rounding.full).background(palette.accent(accent, AccentUse.Mark, tileColor)))
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** The launcher's now-playing slip: one raised card. Folio is a sheet with a hairline; Caelestia a tonal container. */
@Composable
fun Slip(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val palette = LocalKainosPalette.current
    val tokens = LocalSkinTokens.current
    val folio = tokens.skin == Skin.FOLIO
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(if (folio) tokens.rounding.large else tokens.rounding.extraLarge)
    val color = if (folio) palette.sheet else MaterialTheme.colorScheme.surfaceContainer
    val border = if (folio) BorderStroke(1.dp, palette.hair) else null
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = color, border = border, shadowElevation = if (folio) 1.dp else 0.dp, content = content)
    } else {
        Surface(modifier = modifier, shape = shape, color = color, border = border, shadowElevation = if (folio) 1.dp else 0.dp, content = content)
    }
}

/**
 * A section heading. Folio sets it in italic serif over a hairline rule, like the launcher's
 * almanac; Caelestia uses the shell's medium title.
 */
@Composable
fun SectionRule(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val palette = LocalKainosPalette.current
    val tokens = LocalSkinTokens.current
    val folio = tokens.skin == Skin.FOLIO
    Row(
        modifier.fillMaxWidth().then(
            if (folio) Modifier.drawBehind {
                drawLine(palette.rule, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            } else Modifier,
        ).padding(vertical = if (folio) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = if (folio) MaterialTheme.typography.titleMedium.copy(fontStyle = FontStyle.Italic) else MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke(this)
    }
}

/** One row of a hairline ledger. Folio rules each row; Caelestia leaves rows open on the surface. */
@Composable
fun LedgerRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    highlighted: Boolean = false,
    header: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val palette = LocalKainosPalette.current
    val tokens = LocalSkinTokens.current
    val folio = tokens.skin == Skin.FOLIO
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(if (folio) 0.dp else tokens.rounding.normal)
    val fill = when {
        highlighted -> if (folio) palette.sheet else MaterialTheme.colorScheme.secondaryContainer
        header && !folio -> MaterialTheme.colorScheme.surfaceContainer
        else -> Color.Transparent
    }
    val line = if (header) palette.rule else palette.hair
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(fill)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(
                if (folio) Modifier.drawBehind {
                    drawLine(line, Offset(0f, size.height - .5f), Offset(size.width, size.height - .5f), 1.dp.toPx())
                    if (highlighted) drawLine(palette.scheme.primary, Offset(1f, 0f), Offset(1f, size.height), 2.dp.toPx())
                } else Modifier,
            )
            .padding(horizontal = 8.dp, vertical = if (header) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** Ledger header cell text: italic serif in Folio, label style in Caelestia. */
@Composable
fun LedgerHeaderText(text: String, modifier: Modifier = Modifier) {
    val folio = LocalSkinTokens.current.skin == Skin.FOLIO
    Text(
        text,
        modifier = modifier,
        style = if (folio) MaterialTheme.typography.labelMedium.copy(fontFamily = LocalSkinTokens.current.display, fontStyle = FontStyle.Italic)
        else MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/**
 * A filter chip that answers "is this on?" plainly: Folio fills it with ink and a check, like the
 * launcher's week strip; Caelestia uses the shell's secondary container. [accent] tints the
 * unselected leading mark so each filter keeps its hue.
 */
@Composable
fun KainosChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    accent: AccentSlot? = null,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val palette = LocalKainosPalette.current
    val tokens = LocalSkinTokens.current
    val folio = tokens.skin == Skin.FOLIO
    val scheme = MaterialTheme.colorScheme
    val selectedFill = if (folio) scheme.onBackground else scheme.secondaryContainer
    val selectedInk = if (folio) scheme.background else scheme.onSecondaryContainer
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        label = { Text(label, maxLines = 1) },
        shape = if (folio) tokens.rounding.full else MaterialTheme.shapes.small,
        leadingIcon = {
            when {
                selected -> Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                leadingIcon != null -> Icon(leadingIcon, null, Modifier.size(16.dp), tint = accent?.let { palette.accent(it, AccentUse.Mark) } ?: scheme.onSurfaceVariant)
                accent != null -> Box(Modifier.size(8.dp).clip(tokens.rounding.full).background(palette.accent(accent, AccentUse.Mark)))
            }
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            selectedContainerColor = selectedFill,
            selectedLabelColor = selectedInk,
            selectedLeadingIconColor = selectedInk,
            labelColor = scheme.onSurface,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = if (folio) palette.rule else scheme.outline,
            selectedBorderColor = Color.Transparent,
        ),
    )
}

/**
 * Placeholder art when a track has no cover: the key's accent hue as a soft diagonal, with its
 * initial set in the display face.
 */
@Composable
fun PlaceholderArt(seed: String, modifier: Modifier = Modifier) {
    val palette = LocalKainosPalette.current
    val tokens = LocalSkinTokens.current
    val hue = palette.accents.forKey(seed)
    val deep = lerp(hue, palette.scheme.onBackground.takeIf { !palette.isDark } ?: Color.Black, .35f)
    val ink = contrastingColor(palette.ground, deep, palette.ground, 3.0)
    Box(
        modifier.background(Brush.linearGradient(listOf(hue, deep))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            seed.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?",
            style = MaterialTheme.typography.headlineSmall.copy(fontFamily = tokens.display),
            color = ink,
        )
    }
}

/** Small accent-filled badge (quality, counts). Text resolves against its own container. */
@Composable
fun AccentBadge(text: String, slot: AccentSlot, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val palette = LocalKainosPalette.current
    val tokens = LocalSkinTokens.current
    val container = palette.accentContainer(slot)
    Row(
        modifier.widthIn(min = 0.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(tokens.rounding.small))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val ink = palette.accent(slot, AccentUse.Text, container)
        icon?.let { Icon(it, null, Modifier.size(14.dp), tint = ink) }
        Text(text, style = MaterialTheme.typography.labelSmall, color = ink, maxLines = 1)
    }
}


/** The main play control: Folio's ink circle, Caelestia's wide filled primary button. */
@Composable
fun PlayButton(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 72.dp,
) {
    val tokens = LocalSkinTokens.current
    val scheme = MaterialTheme.colorScheme
    val folio = tokens.skin == Skin.FOLIO
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = if (folio) tokens.rounding.full else androidx.compose.foundation.shape.RoundedCornerShape(tokens.rounding.large),
        color = if (folio) scheme.onBackground else scheme.primary,
        contentColor = if (folio) scheme.background else scheme.onPrimary,
        modifier = modifier.size(if (folio) size else size * 1.25f, if (folio) size else size * .8f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                modifier = Modifier.size(size / 2),
            )
        }
    }
}

/**
 * An icon toggle whose on state is a filled container in its accent hue, so "on" never rests on
 * tint alone. Caelestia squares the container when on, the shell's checked-button morph.
 */
@Composable
fun AccentToggle(
    checked: Boolean,
    onClick: () -> Unit,
    slot: AccentSlot,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val palette = LocalKainosPalette.current
    val tokens = LocalSkinTokens.current
    val container = palette.accentContainer(slot)
    val shape = if (checked && tokens.skin == Skin.CAELESTIA) {
        androidx.compose.foundation.shape.RoundedCornerShape(tokens.rounding.normal)
    } else {
        tokens.rounding.full
    }
    androidx.compose.material3.IconToggleButton(
        checked = checked,
        onCheckedChange = { onClick() },
        enabled = enabled,
        shape = shape,
        modifier = modifier,
        colors = androidx.compose.material3.IconButtonDefaults.iconToggleButtonColors(
            checkedContainerColor = container,
            checkedContentColor = palette.accent(slot, AccentUse.Mark, container),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Icon(icon, contentDescription = contentDescription)
    }
}

/**
 * A screen's title, set large in the display face. [ghost] (a count or a time) sits behind it as an
 * outlined numeral, the launcher almanac's ghost; [eyebrow] is a short italic line above.
 */
@Composable
fun DisplayHero(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    ghost: String? = null,
    accent: AccentSlot? = null,
) {
    val palette = LocalKainosPalette.current
    val tokens = LocalSkinTokens.current
    val typography = MaterialTheme.typography
    Column(modifier.fillMaxWidth()) {
        eyebrow?.let {
            Text(
                it,
                style = typography.titleSmall.copy(fontFamily = tokens.display, fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(contentAlignment = Alignment.CenterStart) {
            ghost?.let {
                Text(
                    it,
                    style = typography.displayLarge.copy(
                        fontFamily = tokens.display,
                        fontSize = typography.displayLarge.fontSize * 1.5f,
                        drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f),
                    ),
                    color = (accent?.let { slot -> palette.accent(slot) } ?: palette.rule).copy(alpha = .22f),
                    modifier = Modifier.clearAndSetSemantics { },
                    maxLines = 1,
                )
            }
            Text(
                title,
                style = typography.displayMedium.copy(fontFamily = tokens.display),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = if (ghost != null) 12.dp else 0.dp),
            )
        }
    }
}
