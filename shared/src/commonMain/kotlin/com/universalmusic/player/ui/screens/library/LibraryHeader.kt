package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.ui.reportsTextInputFocus
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryCollapsingHeader(
    scrollBehavior: TopAppBarScrollBehavior,
    chromeHeightPx: Int,
    onChromeHeightPx: (Int) -> Unit,
    queryField: TextFieldValue,
    onQueryField: (TextFieldValue) -> Unit,
    searchFocusRequester: FocusRequester,
    onSearchFocusChanged: (Boolean) -> Unit,
    tab: LibraryTab,
    songs: List<Track>,
    queueSongs: List<Track>,
    onPlayTracks: (List<Track>, Int) -> Unit,
    localState: ProviderState,
    onRefreshLocal: () -> Unit,
    showSpotifyError: Boolean,
    spotifyError: String?,
    onDismissSpotifyError: () -> Unit,
) {
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val heightOffset = scrollBehavior.state.heightOffset
    val chromeModifier = if (chromeHeightPx == 0) {
        Modifier.fillMaxWidth()
    } else {
        Modifier
            .fillMaxWidth()
            .height(with(density) { (chromeHeightPx + heightOffset).coerceAtLeast(0f).toDp() })
            .clipToBounds()
    }
    Column(chromeModifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(unbounded = true, align = Alignment.Top)
                .onSizeChanged { size ->
                    if (size.height > 0 && size.height != chromeHeightPx) {
                        onChromeHeightPx(size.height)
                        scrollBehavior.state.heightOffsetLimit = -size.height.toFloat()
                    }
                }
                .offset {
                    IntOffset(0, if (chromeHeightPx == 0) 0 else heightOffset.roundToInt())
                },
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Library", style = MaterialTheme.typography.headlineMedium)
                IconButton(
                    onClick = onRefreshLocal,
                    enabled = localState != ProviderState.LOADING,
                ) {
                    if (localState == ProviderState.LOADING) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Rescan music folders",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            OutlinedTextField(
                value = queryField,
                onValueChange = onQueryField,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp)
                    .focusRequester(searchFocusRequester)
                    .onFocusChanged { onSearchFocusChanged(it.isFocused) }
                    .reportsTextInputFocus()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter, Key.NumPadEnter -> {
                                val top = songs.firstOrNull()
                                if (tab != LibraryTab.Songs || top == null) return@onPreviewKeyEvent false
                                val index = queueSongs.indexOfFirst { it.canonicalId == top.canonicalId }
                                    .coerceAtLeast(0)
                                onPlayTracks(queueSongs, index)
                                focusManager.clearFocus()
                                true
                            }
                            Key.Escape -> {
                                onQueryField(TextFieldValue(""))
                                focusManager.clearFocus()
                                true
                            }
                            else -> false
                        }
                    },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("Search library") },
                // Phone keyboard: the Search key just hides the keyboard so the ranked results show.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                trailingIcon = {
                    if (queryField.text.isNotEmpty()) {
                        IconButton(onClick = { onQueryField(TextFieldValue("")) }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear library search")
                        }
                    }
                },
            )
            if (showSpotifyError) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        spotifyError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismissSpotifyError) {
                        Text("Dismiss")
                    }
                }
            }
        }
    }
}
