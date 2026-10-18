package com.universalmusic.player.ui.theme

import androidx.compose.ui.graphics.Color
import com.universalmusic.player.data.settings.FolioTint

// Generated from the approved mockup by scripts/generate-folio-tints.cjs.
internal data class RawFolio(
    val paper: Color, val sheet: Color, val field: Color, val tile: Color,
    val hair: Color, val rule: Color, val ink: Color, val muted: Color,
    val accent: Color, val hues: AccentRamp,
)

internal val folioTints = mapOf(
    FolioTint.FOLIO to Pair(
        RawFolio(Color(0xFFE8EAE9), Color(0xFFF4F5F4), Color(0xFFFFFFFF), Color(0xFFF9FAF9), Color(0xFFC9CECD), Color(0xFFB5BAB9), Color(0xFF15181B), Color(0xFF5F666C), Color(0xFF1E4BD8), AccentRamp(Color(0xFFE5484D), Color(0xFFFF8A3D), Color(0xFFF5B800), Color(0xFF30A46C), Color(0xFF12A594), Color(0xFF2D8CFF), Color(0xFF6E56CF), Color(0xFFF06292), Color(0xFF707785))),
        RawFolio(Color(0xFF1E1D1B), Color(0xFF262523), Color(0xFF2E2D2A), Color(0xFF2A2927), Color(0xFF3B3A37), Color(0xFF4A4844), Color(0xFFECE9E2), Color(0xFF9A978F), Color(0xFF8FA3FF), AccentRamp(Color(0xFFE5484D), Color(0xFFFF8A3D), Color(0xFFF5B800), Color(0xFF30A46C), Color(0xFF12A594), Color(0xFF2D8CFF), Color(0xFF6E56CF), Color(0xFFF06292), Color(0xFF707785))),
    ),
    FolioTint.EVERFOREST to Pair(
        RawFolio(Color(0xFFFDF6E3), Color(0xFFF4F0D9), Color(0xFFFFFBEF), Color(0xFFFFFBEF), Color(0xFFE0DCC7), Color(0xFFBDC3AF), Color(0xFF5C6A72), Color(0xFF829181), Color(0xFF8DA101), AccentRamp(Color(0xFFF85552), Color(0xFFF57D26), Color(0xFFDFA000), Color(0xFF8DA101), Color(0xFF35A77C), Color(0xFF3A94C5), Color(0xFFDF69BA), Color(0xFFDF69BA), Color(0xFF939F91))),
        RawFolio(Color(0xFF2D353B), Color(0xFF343F44), Color(0xFF3D484D), Color(0xFF343F44), Color(0xFF475258), Color(0xFF4F585E), Color(0xFFD3C6AA), Color(0xFF9DA9A0), Color(0xFFA7C080), AccentRamp(Color(0xFFE67E80), Color(0xFFE69875), Color(0xFFDBBC7F), Color(0xFFA7C080), Color(0xFF83C092), Color(0xFF7FBBB3), Color(0xFFD699B6), Color(0xFFD699B6), Color(0xFF859289))),
    ),
    FolioTint.CATPPUCCIN to Pair(
        RawFolio(Color(0xFFEFF1F5), Color(0xFFE6E9EF), Color(0xFFfafbfc), Color(0xFFf8f9fb), Color(0xFFCCD0DA), Color(0xFFACB0BE), Color(0xFF4C4F69), Color(0xFF6C6F85), Color(0xFF179299), AccentRamp(Color(0xFFD20F39), Color(0xFFFE640B), Color(0xFFDF8E1D), Color(0xFF40A02B), Color(0xFF179299), Color(0xFF1E66F5), Color(0xFF8839EF), Color(0xFFEA76CB), Color(0xFF8C8FA1))),
        RawFolio(Color(0xFF1E1E2E), Color(0xFF272738), Color(0xFF313244), Color(0xFF292a3b), Color(0xFF45475A), Color(0xFF585B70), Color(0xFFCDD6F4), Color(0xFFA6ADC8), Color(0xFF94E2D5), AccentRamp(Color(0xFFF38BA8), Color(0xFFFAB387), Color(0xFFF9E2AF), Color(0xFFA6E3A1), Color(0xFF94E2D5), Color(0xFF89B4FA), Color(0xFFCBA6F7), Color(0xFFF5C2E7), Color(0xFF9399B2))),
    ),
    FolioTint.GRUVBOX to Pair(
        RawFolio(Color(0xFFFBF1C7), Color(0xFFF2E5BC), Color(0xFFF9F5D7), Color(0xFFF9F5D7), Color(0xFFD5C4A1), Color(0xFFBDAE93), Color(0xFF3C3836), Color(0xFF7C6F64), Color(0xFFAF3A03), AccentRamp(Color(0xFF9D0006), Color(0xFFAF3A03), Color(0xFFB57614), Color(0xFF79740E), Color(0xFF427B58), Color(0xFF076678), Color(0xFF8F3F71), Color(0xFF8F3F71), Color(0xFF928374))),
        RawFolio(Color(0xFF282828), Color(0xFF32302F), Color(0xFF3C3836), Color(0xFF32302F), Color(0xFF504945), Color(0xFF665C54), Color(0xFFEBDBB2), Color(0xFFA89984), Color(0xFFFE8019), AccentRamp(Color(0xFFFB4934), Color(0xFFFE8019), Color(0xFFFABD2F), Color(0xFFB8BB26), Color(0xFF8EC07C), Color(0xFF83A598), Color(0xFFD3869B), Color(0xFFD3869B), Color(0xFF928374))),
    ),
    FolioTint.NORD to Pair(
        RawFolio(Color(0xFFECEFF4), Color(0xFFE5E9F0), Color(0xFFf7f9fb), Color(0xFFf6f7fa), Color(0xFFD8DEE9), Color(0xFFbcc3d0), Color(0xFF2E3440), Color(0xFF4C566A), Color(0xFF5E81AC), AccentRamp(Color(0xFFBF616A), Color(0xFFD08770), Color(0xFFEBCB8B), Color(0xFFA3BE8C), Color(0xFF8FBCBB), Color(0xFF5E81AC), Color(0xFFB48EAD), Color(0xFFB48EAD), Color(0xFF4C566A))),
        RawFolio(Color(0xFF2E3440), Color(0xFF353b49), Color(0xFF3B4252), Color(0xFF3B4252), Color(0xFF434C5E), Color(0xFF4C566A), Color(0xFFECEFF4), Color(0xFFaeb5c3), Color(0xFF88C0D0), AccentRamp(Color(0xFFBF616A), Color(0xFFD08770), Color(0xFFEBCB8B), Color(0xFFA3BE8C), Color(0xFF8FBCBB), Color(0xFF81A1C1), Color(0xFFB48EAD), Color(0xFFB48EAD), Color(0xFF616E88))),
    ),
    FolioTint.ROSE_PINE to Pair(
        RawFolio(Color(0xFFFAF4ED), Color(0xFFFFFAF3), Color(0xFFFFFAF3), Color(0xFFFFFAF3), Color(0xFFDFDAD9), Color(0xFFCECACD), Color(0xFF575279), Color(0xFF797593), Color(0xFF907AA9), AccentRamp(Color(0xFFB4637A), Color(0xFFD7827E), Color(0xFFEA9D34), Color(0xFF56949F), Color(0xFF56949F), Color(0xFF286983), Color(0xFF907AA9), Color(0xFFD7827E), Color(0xFF9893A5))),
        RawFolio(Color(0xFF191724), Color(0xFF1F1D2E), Color(0xFF26233A), Color(0xFF1F1D2E), Color(0xFF403D52), Color(0xFF524F67), Color(0xFFE0DEF4), Color(0xFF908CAA), Color(0xFFC4A7E7), AccentRamp(Color(0xFFEB6F92), Color(0xFFEBBCBA), Color(0xFFF6C177), Color(0xFF9CCFD8), Color(0xFF9CCFD8), Color(0xFF31748F), Color(0xFFC4A7E7), Color(0xFFEBBCBA), Color(0xFF6E6A86))),
    ),
    FolioTint.TOKYO_NIGHT to Pair(
        RawFolio(Color(0xFFE1E2E7), Color(0xFFE9E9ED), Color(0xFFf4f4f6), Color(0xFFf2f2f4), Color(0xFFC4C8DA), Color(0xFFA8AECB), Color(0xFF3760BF), Color(0xFF68709A), Color(0xFF9854F1), AccentRamp(Color(0xFFF52A65), Color(0xFFB15C00), Color(0xFF8C6C3E), Color(0xFF587539), Color(0xFF118C74), Color(0xFF2E7DE9), Color(0xFF9854F1), Color(0xFFD20065), Color(0xFF848CB5))),
        RawFolio(Color(0xFF1A1B26), Color(0xFF1F2335), Color(0xFF292E42), Color(0xFF1F2335), Color(0xFF3B4261), Color(0xFF414868), Color(0xFFC0CAF5), Color(0xFF737AA2), Color(0xFFBB9AF7), AccentRamp(Color(0xFFF7768E), Color(0xFFFF9E64), Color(0xFFE0AF68), Color(0xFF9ECE6A), Color(0xFF73DACA), Color(0xFF7AA2F7), Color(0xFFBB9AF7), Color(0xFFFF007C), Color(0xFF565F89))),
    ),
)
