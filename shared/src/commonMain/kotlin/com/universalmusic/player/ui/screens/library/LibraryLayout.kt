package com.universalmusic.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import com.universalmusic.player.ui.components.isFrost
import com.universalmusic.player.ui.theme.LocalKainosPalette

/** At this width Library's title, search and filters share one line, and the tabs share theirs with the count. */
internal val LibraryCompactHeaderMinWidth: Dp = 1500.dp

/** At this width Songs stands as its own pane beside the other four tabs. */
internal val LibrarySplitMinWidth: Dp = 2000.dp

/** The share of a split Library that Songs takes; the other tabs get the rest. */
internal const val LibrarySongsShare: Float = .55f

/** The seam between the two panes of a split Library: a hairline with 8dp of air on each side. */
internal val LibraryPaneSeamWidth: Dp = 17.dp

/** How Library lays itself out at its own width, decided once per screen. */
internal data class LibraryLayout(
    val compactHeader: Boolean,
    val split: Boolean,
    /** Width of the list that shows Songs. */
    val songsWidth: Dp,
    /** Width of the list that shows Albums, Artists, Playlists or Folders. */
    val otherWidth: Dp,
)

internal fun libraryLayout(width: Dp): LibraryLayout {
    val split = width >= LibrarySplitMinWidth
    val panes = width - LibraryPaneSeamWidth
    return LibraryLayout(
        compactHeader = width >= LibraryCompactHeaderMinWidth,
        split = split,
        songsWidth = if (split) panes * LibrarySongsShare else width,
        otherWidth = if (split) panes * (1f - LibrarySongsShare) else width,
    )
}

/**
 * What a split Library's right pane shows: the selected tab, or while Songs is selected (Songs has
 * the left pane to itself) the last other tab the listener chose.
 */
internal fun libraryOtherTab(selected: LibraryTab, lastOther: LibraryTab): LibraryTab = when {
    selected != LibraryTab.Songs -> selected
    lastOther != LibraryTab.Songs -> lastOther
    else -> LibraryTab.Albums
}

/** Tabs at their natural width with an ink underline under the chosen one, so a wide pane is not five islands. */
@Composable
internal fun LibraryTabStrip(
    tabs: List<LibraryTab>,
    selected: LibraryTab,
    onSelect: (LibraryTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    Row(modifier.selectableGroup()) {
        tabs.forEach { item ->
            val on = item == selected
            Tab(
                selected = on,
                onClick = { onSelect(item) },
                text = { Text(item.name, maxLines = 1) },
                selectedContentColor = ink,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(min = 104.dp).drawBehind {
                    if (on) {
                        val line = 2.dp.toPx()
                        drawRect(ink, Offset(0f, size.height - line), Size(size.width, line))
                    }
                },
            )
        }
    }
}

/** The line between Songs and the other tabs: a quiet hairline inside the one content sheet. */
@Composable
internal fun LibraryPaneSeam() {
    val line = if (isFrost()) LocalKainosPalette.current.hair else MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier
            .width(LibraryPaneSeamWidth)
            .fillMaxHeight()
            .padding(horizontal = 8.dp, vertical = 12.dp)
            .background(line),
    )
}

/** Calls [onPress] whenever a press lands anywhere inside, without taking the press from what is under it. */
@Composable
internal fun Modifier.onAnyPress(onPress: () -> Unit): Modifier {
    val latest by rememberUpdatedState(onPress)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press) latest()
            }
        }
    }
}
