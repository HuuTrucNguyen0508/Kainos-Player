package com.universalmusic.player.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.universalmusic.player.data.settings.FolioTint
import com.universalmusic.player.ui.theme.folioPalette
import kotlin.test.Test
import kotlin.test.assertTrue

class FailureNoticeTest {
    private fun contrast(a: Color, b: Color): Float {
        val (dark, light) = listOf(a.luminance(), b.luminance()).sorted()
        return (light + 0.05f) / (dark + 0.05f)
    }

    @Test fun failureTextStaysReadableInEveryScheme() {
        for (tint in FolioTint.entries) for (dark in listOf(false, true)) {
            val scheme = folioPalette(tint, dark).scheme
            val ratio = contrast(scheme.onErrorContainer, scheme.errorContainer)
            assertTrue(ratio >= 4.5f, "$tint dark=$dark contrast is only $ratio")
        }
    }
}
