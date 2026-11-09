package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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

/** At this width Library's title, search and filters share one line, and the tabs share theirs with the count. */
internal val LibraryCompactHeaderMinWidth: Dp = 1500.dp

/** At this width Songs stands as its own pane beside the other four tabs. */
internal val LibrarySplitMinWidth: Dp = 2000.dp

/** The share of a split Library that Songs takes; the other tabs get the rest. */
internal const val LibrarySongsShare: Float = .55f

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
    val panes = width - ContentPaneSeamWidth
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
internal fun libraryOtherTab(selected: LibraryTab, lastOther: LibraryTab?, populated: Set<LibraryTab> = emptySet()): LibraryTab = when {
    selected != LibraryTab.Songs -> selected
    lastOther != null && lastOther != LibraryTab.Songs -> lastOther
    else -> listOf(LibraryTab.Albums, LibraryTab.Artists, LibraryTab.Playlists, LibraryTab.Folders)
        .firstOrNull { it in populated } ?: LibraryTab.Albums
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
                // A Tab fills the width it is offered; its text's own width keeps the row at natural size.
                modifier = Modifier.widthIn(min = 104.dp).width(IntrinsicSize.Max).drawBehind {
                    if (on) {
                        val line = 2.dp.toPx()
                        drawRect(ink, Offset(0f, size.height - line), Size(size.width, line))
                    }
                },
            )
        }
    }
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
