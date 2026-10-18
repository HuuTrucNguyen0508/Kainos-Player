package com.universalmusic.player.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.universalmusic.player.data.settings.SkinChoice
import com.universalmusic.player.platform.platformLabel

enum class Skin { FOLIO, CAELESTIA }
enum class SeekStyle { RULED, WAVY }
enum class GroundStyle { DOTTED_PAPER, PLAIN }

data class Rounding(
    val extraSmall: Dp, val small: Dp, val normal: Dp, val large: Dp,
    val largeIncreased: Dp, val extraLarge: Dp, val extraLargeIncreased: Dp,
    val extraExtraLarge: Dp,
    val full: RoundedCornerShape = RoundedCornerShape(50),
)

data class MotionSpec(val durationMs: Int, val easing: Easing)

data class Motion(
    val standard: MotionSpec,
    val spatial: MotionSpec,
    val fastSpatial: MotionSpec,
    val effects: MotionSpec,
    val slowEffects: MotionSpec,
)

data class SkinTokens(
    val skin: Skin,
    val typography: Typography,
    val display: FontFamily,
    val shapes: Shapes,
    val rounding: Rounding,
    val motion: Motion,
    val seek: SeekStyle,
    val ground: GroundStyle,
)

fun resolveSkin(choice: SkinChoice, platform: String = platformLabel()): Skin = when (choice) {
    SkinChoice.FOLIO -> Skin.FOLIO
    SkinChoice.CAELESTIA -> Skin.CAELESTIA
    SkinChoice.PLATFORM_DEFAULT -> if (platform == "Android") Skin.FOLIO else Skin.CAELESTIA
}

internal expect fun caelestiaFontFamily(): FontFamily

fun skinTokens(skin: Skin): SkinTokens {
    val folio = skin == Skin.FOLIO
    val display = if (folio) FontFamily.Serif else caelestiaFontFamily()
    val body = if (folio) FontFamily.SansSerif else display
    fun style(size: Int, family: FontFamily = body, medium: Boolean = false) = TextStyle(
        fontFamily = family, fontSize = size.sp, lineHeight = (size * 1.3f).sp,
        fontWeight = if (medium) FontWeight.Medium else FontWeight.Normal,
    )
    val typography = if (folio) Typography(
        displayLarge = style(57, display), displayMedium = style(45, display), displaySmall = style(36, display),
        headlineLarge = style(32, display), headlineMedium = style(28, display), headlineSmall = style(24, display),
        titleLarge = style(22, display), titleMedium = style(18, display), titleSmall = style(16, display),
        bodyLarge = style(16), bodyMedium = style(14), bodySmall = style(12),
        labelLarge = style(14, medium = true), labelMedium = style(12, medium = true), labelSmall = style(11),
    ) else Typography(
        displayLarge = style(57, display, true), displayMedium = style(45, display, true), displaySmall = style(42, display, true),
        headlineLarge = style(42, display, true), headlineMedium = style(37, display, true), headlineSmall = style(32, display, true),
        titleLarge = style(29, display, true), titleMedium = style(21, display, true), titleSmall = style(18, display, true),
        bodyLarge = style(21), bodyMedium = style(18), bodySmall = style(16),
        labelLarge = style(18, medium = true), labelMedium = style(16, medium = true), labelSmall = style(14),
    )
    val rounding = if (folio) Rounding(2.dp, 4.dp, 8.dp, 12.dp, 16.dp, 20.dp, 24.dp, 32.dp)
        else Rounding(4.dp, 8.dp, 12.dp, 16.dp, 20.dp, 28.dp, 32.dp, 48.dp)
    val standard = CubicBezierEasing(.2f, 0f, 0f, 1f)
    val motion = if (folio) Motion(
        MotionSpec(180, standard), MotionSpec(220, standard), MotionSpec(140, standard),
        MotionSpec(120, standard), MotionSpec(180, standard),
    ) else Motion(
        MotionSpec(300, standard), MotionSpec(500, CubicBezierEasing(.38f, 1.21f, .22f, 1f)),
        MotionSpec(350, CubicBezierEasing(.42f, 1.67f, .21f, .9f)),
        MotionSpec(200, CubicBezierEasing(.34f, .8f, .34f, 1f)),
        MotionSpec(300, CubicBezierEasing(.34f, .88f, .34f, 1f)),
    )
    return SkinTokens(skin, typography, display, Shapes(
        extraSmall = RoundedCornerShape(rounding.extraSmall), small = RoundedCornerShape(rounding.small),
        medium = RoundedCornerShape(rounding.normal), large = RoundedCornerShape(rounding.large),
        extraLarge = RoundedCornerShape(rounding.extraLarge),
    ), rounding, motion, if (folio) SeekStyle.RULED else SeekStyle.WAVY,
        if (folio) GroundStyle.DOTTED_PAPER else GroundStyle.PLAIN)
}
