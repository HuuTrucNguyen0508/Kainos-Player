package com.universalmusic.player.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive


data class CaelestiaScheme(val isDark: Boolean, val palette: KainosPalette)

fun parseCaelestiaScheme(text: String): CaelestiaScheme? = runCatching {
    val root = Json.parseToJsonElement(text) as? JsonObject ?: return null
    val mode = (root["mode"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> return null }
    val colours = root["colours"] as? JsonObject ?: return null
    fun color(key: String): Color {
        val value = (colours[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        require(value != null && value.length == 6 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
        return Color(0xFF000000L or value.toLong(16))
    }
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primaryFixed = color("primaryFixed"),
        primaryFixedDim = color("primaryFixedDim"),
        secondaryFixed = color("secondaryFixed"),
        secondaryFixedDim = color("secondaryFixedDim"),
        tertiaryFixed = color("tertiaryFixed"),
        tertiaryFixedDim = color("tertiaryFixedDim"),
        onPrimaryFixed = color("onPrimaryFixed"),
        onPrimaryFixedVariant = color("onPrimaryFixedVariant"),
        onSecondaryFixed = color("onSecondaryFixed"),
        onSecondaryFixedVariant = color("onSecondaryFixedVariant"),
        onTertiaryFixed = color("onTertiaryFixed"),
        onTertiaryFixedVariant = color("onTertiaryFixedVariant"),
        primary = color("primary"),
        onPrimary = color("onPrimary"),
        primaryContainer = color("primaryContainer"),
        onPrimaryContainer = color("onPrimaryContainer"),
        inversePrimary = color("inversePrimary"),
        secondary = color("secondary"),
        onSecondary = color("onSecondary"),
        secondaryContainer = color("secondaryContainer"),
        onSecondaryContainer = color("onSecondaryContainer"),
        tertiary = color("tertiary"),
        onTertiary = color("onTertiary"),
        tertiaryContainer = color("tertiaryContainer"),
        onTertiaryContainer = color("onTertiaryContainer"),
        error = color("error"),
        onError = color("onError"),
        errorContainer = color("errorContainer"),
        onErrorContainer = color("onErrorContainer"),
        background = color("background"),
        onBackground = color("onBackground"),
        surface = color("surface"),
        onSurface = color("onSurface"),
        surfaceVariant = color("surfaceVariant"),
        onSurfaceVariant = color("onSurfaceVariant"),
        surfaceTint = color("surfaceTint"),
        inverseSurface = color("inverseSurface"),
        inverseOnSurface = color("inverseOnSurface"),
        outline = color("outline"),
        outlineVariant = color("outlineVariant"),
        scrim = color("scrim"),
        surfaceBright = color("surfaceBright"),
        surfaceDim = color("surfaceDim"),
        surfaceContainerLowest = color("surfaceContainerLowest"),
        surfaceContainerLow = color("surfaceContainerLow"),
        surfaceContainer = color("surfaceContainer"),
        surfaceContainerHigh = color("surfaceContainerHigh"),
        surfaceContainerHighest = color("surfaceContainerHighest"),
    )
    val accents = AccentRamp(
        red = color("red"), orange = color("peach"), yellow = color("yellow"), green = color("green"),
        teal = color("teal"), blue = color(if ("sapphire" in colours) "sapphire" else "blue"),
        purple = color("mauve"), pink = color("pink"), gray = color("overlay1"),
    )
    CaelestiaScheme(dark, KainosPalette(scheme, accents, scheme.background,
        scheme.surfaceContainerLow, scheme.surfaceContainer, scheme.outlineVariant, scheme.outline, dark))
}.getOrNull()

expect fun watchCaelestiaScheme(scope: CoroutineScope): StateFlow<CaelestiaScheme?>
