package com.universalmusic.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.universalmusic.player.ui.components.isFrost
import com.universalmusic.player.ui.theme.LocalKainosPalette

/** The seam between two panes of a split screen: a hairline with 8dp of air on each side. */
internal val ContentPaneSeamWidth: Dp = 17.dp

/** The line between two panes of one screen: a quiet hairline inside the one content sheet. */
@Composable
internal fun ContentPaneSeam() {
    val line = if (isFrost()) LocalKainosPalette.current.hair else MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier
            .width(ContentPaneSeamWidth)
            .fillMaxHeight()
            .padding(horizontal = 8.dp, vertical = 12.dp)
            .background(line),
    )
}
