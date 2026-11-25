package com.universalmusic.player.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Fills, lights, washes and grid ink for the Nyx skin (following `styles/nyx` and T3 Code's Nyx Plus).
 * The violet-to-periwinkle gradient (`#8a7fe8` -> `#6f8cff`) is a declared part of Nyx's identity,
 * limited to lit cells, selection washes and the primary Play button.
 */
data class NyxLight(
    val slab: Color,
    val slabOver: Color,
    val washStart: Color,
    val washMid: Color,
    val washEnd: Color,
    val washFullBase: Color,
    val washFullStart: Color,
    val washFullEnd: Color,
    val underGlow: Color,
    val underGlowSmall: Color,
    val topLight: Color,
    val topLightSmall: Color,
    val buttonBase: Color,
    val buttonTopHighlight: Color,
    val buttonStart: Color,
    val buttonMid: Color,
    val buttonEnd: Color,
    val buttonRing: Color,
    val buttonOuterGlow: Color,
    val meterStart: Color,
    val meterEnd: Color,
    val meterTrack: Color,
    val meterWarn: Color,
    val displayStart: Color,
    val displayEnd: Color,
    val hair: Color,
    val hairViolet: Color,
    val gridLine: Color,
    val inputBase: Color,
    val inputWashStart: Color,
    val inputWashMid: Color,
    val inputRing: Color,
    val highlight: Color,
    val soft: Color,
    val buttonSubtle: Color,
    val hoverRow: Color,
)

val NyxLightTokens = NyxLight(
    slab = Color(0xD103030A),
    slabOver = Color(0xEB03030A),
    washStart = Color(0x528A7FE8),
    washMid = Color(0x1F6F8CFF),
    washEnd = Color.Transparent,
    washFullBase = Color(0xFF0D0B26),
    washFullStart = Color(0x4C8A7FE8),
    washFullEnd = Color(0x1A6F8CFF),
    underGlow = Color(0x8C7864FF),
    underGlowSmall = Color(0x997864FF),
    topLight = Color(0x0AFFFFFF),
    topLightSmall = Color(0x14FFFFFF),
    buttonBase = Color(0xFF0D0B26),
    buttonTopHighlight = Color(0x1FFFFFFF),
    buttonStart = Color(0x808A7FE8),
    buttonMid = Color(0x266F8CFF),
    buttonEnd = Color(0x264A3CC4),
    buttonRing = Color(0x33B4AAFF),
    buttonOuterGlow = Color(0xCCA99FFF),
    meterStart = Color(0xFF8A7FE8),
    meterEnd = Color(0xFF6F8CFF),
    meterTrack = Color(0x1FB4AAFF),
    meterWarn = Color(0xFFE0648F),
    displayStart = Color(0xFFD2C8FF),
    displayEnd = Color(0xFF9AA6FF),
    hair = Color(0x12FFFFFF),
    hairViolet = Color(0x1FB4AAFF),
    gridLine = Color(0xFF000000),
    inputBase = Color(0xE605050F),
    inputWashStart = Color(0x308A7FE8),
    inputWashMid = Color(0x126F8CFF),
    inputRing = Color(0x24B4AAFF),
    highlight = Color(0xFFB4AAFF),
    soft = Color(0xFFE6E2FF),
    buttonSubtle = Color(0x0AFFFFFF),
    hoverRow = Color(0x0AFFFFFF),
)

/**
 * Cell-counted spacing for Nyx: 1..6 cells where one cell is 5 physical device pixels on desktop
 * (read from [LocalDensity] so the 1 px grid never scales) or 3 dp snapped to whole pixels on Android.
 */
data class NyxSpacing(
    val cellPx: Float,
    val cell: Dp,
    val xs: Dp = cell,
    val sm: Dp = cell * 2,
    val md: Dp = cell * 3,
    val lg: Dp = cell * 4,
    val xl: Dp = cell * 5,
    val xxl: Dp = cell * 6,
)

fun nyxSpacing(density: Density): NyxSpacing {
    val px = nyxCellPx(density)
    val cellDp = with(density) { px.toDp() }
    return NyxSpacing(cellPx = px, cell = cellDp)
}

val LocalNyxSpacing = staticCompositionLocalOf { NyxSpacing(cellPx = 5f, cell = 5.dp) }

/**
 * Nyx is dark only. Every Material 3 role is set to its Nyx token so opaque menus, dialogs and
 * controls stay readable and consistent with the desktop shell and Ghostty palette.
 */
fun nyxPalette(): KainosPalette {
    val void = Color(0xFF000000)
    val slabSolid = Color(0xFF03030A)
    val surfaceLow = Color(0xFF05050F)
    val surface = Color(0xFF0D0C20)
    val raised = Color(0xFF110F2A)
    val ansi0 = Color(0xFF1A1840)
    val input = Color(0xFF24224A)
    val select = Color(0xFF2A2660)
    val text = Color(0xFFF1F0FF)
    val muted = Color(0xFFA6A3C0)
    val inkOnLit = Color(0xFF06051A)
    val highlight = Color(0xFFB4AAFF)
    val displayStart = Color(0xFFD2C8FF)
    val periwinkle = Color(0xFF6F8CFF)
    val roseGlow = Color(0xFFFF7AA6)
    val errorContainer = Color(0xFF3A1226)
    val outline = Color(0xFF77739A)
    val outlineVariant = Color(0xFF1F1F27)
    val inversePrimary = Color(0xFF4A3CC4)

    val accents = AccentRamp(
        red = Color(0xFFE0648F),
        orange = Color(0xFFFFA359),
        yellow = Color(0xFFFFD452),
        green = Color(0xFF5ECC71),
        teal = Color(0xFF08C0EF),
        blue = Color(0xFF6F8CFF),
        purple = Color(0xFF9D6AFB),
        pink = Color(0xFFD568EA),
        gray = Color(0xFF8884B0),
    )

    val scheme = darkColorScheme().copy(
        primary = highlight,
        onPrimary = inkOnLit,
        primaryContainer = select,
        onPrimaryContainer = text,
        inversePrimary = inversePrimary,
        secondary = periwinkle,
        onSecondary = inkOnLit,
        secondaryContainer = input,
        onSecondaryContainer = text,
        tertiary = muted,
        onTertiary = inkOnLit,
        tertiaryContainer = raised,
        onTertiaryContainer = text,
        error = roseGlow,
        onError = inkOnLit,
        errorContainer = errorContainer,
        onErrorContainer = text,
        background = void,
        onBackground = text,
        surface = slabSolid,
        onSurface = text,
        surfaceDim = void,
        surfaceBright = raised,
        surfaceContainerLowest = void,
        surfaceContainerLow = surfaceLow,
        surfaceContainer = surface,
        surfaceContainerHigh = raised,
        surfaceContainerHighest = ansi0,
        surfaceVariant = raised,
        onSurfaceVariant = muted,
        surfaceTint = Color.Transparent,
        outline = outline,
        outlineVariant = outlineVariant,
        inverseSurface = text,
        inverseOnSurface = inkOnLit,
        scrim = void,
        primaryFixed = displayStart,
        primaryFixedDim = highlight,
        onPrimaryFixed = inkOnLit,
        onPrimaryFixedVariant = select,
        secondaryFixed = displayStart,
        secondaryFixedDim = highlight,
        onSecondaryFixed = inkOnLit,
        onSecondaryFixedVariant = select,
        tertiaryFixed = displayStart,
        tertiaryFixedDim = highlight,
        onTertiaryFixed = inkOnLit,
        onTertiaryFixedVariant = select,
    )

    return KainosPalette(
        scheme = scheme,
        accents = accents,
        ground = void,
        sheet = surface,
        tile = surface,
        hair = NyxLightTokens.hair,
        rule = NyxLightTokens.hairViolet,
        isDark = true,
    )
}
