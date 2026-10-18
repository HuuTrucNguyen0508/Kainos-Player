package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.universalmusic.player.data.library.HomePinKind
import com.universalmusic.player.data.settings.FolioTint
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderEntityRef
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.ui.components.Slip
import com.universalmusic.player.ui.theme.KainosTheme
import com.universalmusic.player.ui.theme.Skin
import com.universalmusic.player.ui.theme.folioPalette
import com.universalmusic.player.ui.theme.skinTokens
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
class HomeLayoutTest {
    @Test
    fun suggestionPinHasItsOwnFortyEightDpTargetInBothSkins() {
        for (skin in Skin.entries) {
            var pins = 0
            var plays = 0
            withScene(skin, 360) { inspect ->
                inspect {
                    PinSuggestionsSection(
                        listOf(PinSuggestion(HomePinKind.LOCAL_FOLDER, "/music", "Music", "Folder", null)),
                        onPlay = { plays++ },
                        onPin = { pins++ },
                    )
                }
                val icon = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Pin Music to Home") == true }
                val button = generateSequence(icon) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick) != null }
                assertTrue(button.boundsInRoot.width >= 48f && button.boundsInRoot.height >= 48f, "$skin pin bounds ${button.boundsInRoot}")
                button.config[SemanticsActions.OnClick].action!!.invoke()
                assertEquals(1, pins)
                assertEquals(0, plays)
            }
        }
    }

    @Test
    fun wideCardsSupportIntrinsicMeasurementWithDiscoverInBothSkins() {
        val lines = libraryLines(42, 1, ProviderState.AVAILABLE, FavoritesSummary(8, 8, 0, 0, emptyList()), 2,
            ProviderState.AVAILABLE, 3, ProviderState.AVAILABLE)
        for (skin in Skin.entries) for (dark in listOf(false, true)) {
            withScene(skin, 900, dark) { inspect ->
                inspect {
                    Column(Modifier.fillMaxSize().padding(28.dp)) {
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Slip(Modifier.weight(1f).fillMaxHeight()) {
                                DiscoverBlock(Playlist("spotify-playlist:weekly", "Weekly", trackCount = 20,
                                    source = ProviderEntityRef(ProviderId.SPOTIFY, "weekly")), false, {})
                            }
                            FavoritesHero(FavoritesSummary(8, 8, 0, 0, emptyList()), true, {}, {}, Modifier.weight(1.25f).fillMaxHeight())
                            LibraryCard(lines, {}, Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
                val text = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                assertTrue("Play favorites" in text && "Shuffle" in text && "Your library" in text && "Weekly" in text)
            }
        }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> = semanticsOwners.flatMap { owner ->
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        descendants(owner.rootSemanticsNode)
    }

    private fun withScene(skin: Skin, width: Int, dark: Boolean = false, check: ImageComposeScene.((@Composable () -> Unit) -> Unit) -> Unit) {
        val scheduler = TestCoroutineScheduler()
        val palette = folioPalette(FolioTint.FOLIO, dark)
        val scene = ImageComposeScene(width = width, height = 900, density = Density(1f), coroutineContext = StandardTestDispatcher(scheduler))
        try {
            scene.check { content ->
                scene.setContent {
                    KainosTheme(palette, skinTokens(skin)) {
                        Surface(Modifier.fillMaxSize(), color = palette.ground) { content() }
                    }
                }
                repeat(3) {
                    scene.render(0).close()
                    scheduler.runCurrent()
                }
            }
        } finally {
            scene.close()
        }
    }
}
