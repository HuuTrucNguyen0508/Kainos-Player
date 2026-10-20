package com.universalmusic.player.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.data.settings.FolioTint
import com.universalmusic.player.data.settings.SkinChoice
import com.universalmusic.player.data.settings.ThemeMode
import com.universalmusic.player.platform.platformLabel

val LocalKainosPalette = staticCompositionLocalOf { folioPalette(FolioTint.FOLIO, false) }
val LocalSkinTokens = staticCompositionLocalOf { skinTokens(Skin.FOLIO) }
val LocalFollowingCaelestia = staticCompositionLocalOf { false }

data class ResolvedTheme(val skin: Skin, val palette: KainosPalette, val followsCaelestia: Boolean)

fun resolveTheme(
    settings: AppSettings,
    systemDark: Boolean,
    scheme: CaelestiaScheme?,
    platform: String = platformLabel(),
    desktopEnv: Skin? = null,
): ResolvedTheme {
    val skin = resolveSkin(settings.skin, platform, desktopEnv)
    val following = platform != "Android" && settings.skin != SkinChoice.FOLIO && settings.followCaelestiaScheme && scheme != null
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    return ResolvedTheme(skin, if (following) scheme.palette else folioPalette(settings.tint, dark), following)
}

@Composable
fun KainosTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val source = remember(scope) { watchCaelestiaScheme(scope) }
    val scheme by source.collectAsState()
    val desktopSource = remember(scope) { watchDesktopEnv(scope) }
    val desktopEnv by desktopSource.collectAsState()
    val resolved = resolveTheme(settings, isSystemInDarkTheme(), scheme, desktopEnv = desktopEnv)
    val tokens = remember(resolved.skin) { skinTokens(resolved.skin) }
    KainosTheme(resolved.palette, tokens, resolved.followsCaelestia, content)
}

@Composable
fun KainosTheme(
    palette: KainosPalette,
    tokens: SkinTokens,
    followsCaelestia: Boolean = false,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalKainosPalette provides palette,
        LocalSkinTokens provides tokens,
        LocalFollowingCaelestia provides followsCaelestia,
    ) {
        MaterialTheme(colorScheme = palette.scheme, typography = tokens.typography, shapes = tokens.shapes, content = content)
    }
}

@Composable
fun appColorScheme(): ColorScheme = MaterialTheme.colorScheme
