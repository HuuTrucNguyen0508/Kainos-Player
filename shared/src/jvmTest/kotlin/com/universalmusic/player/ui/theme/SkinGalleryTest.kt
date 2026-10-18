package com.universalmusic.player.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.universalmusic.player.data.settings.FolioTint
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkinGalleryTest {
    @Test fun primitiveRendersToPng() {
        val palette = folioPalette(FolioTint.FOLIO, false)
        val output = Path.of(System.getProperty("kainos.skinGalleryDir"), "proof", "swatch-row.png")
        val render = {
            renderSkinPng(output, 180, 40, palette, skinTokens(Skin.FOLIO)) {
                Row {
                    AccentSlot.entries.forEach { slot -> Box(Modifier.size(20.dp).background(palette.accents[slot])) }
                }
            }
        }
        render()
        val first = Files.readAllBytes(output)
        render()
        assertContentEquals(first, Files.readAllBytes(output))
        val png = ImageIO.read(output.toFile())
        assertEquals(180, png.width)
        assertEquals(40, png.height)
        assertEquals(0xFFE5484D.toInt(), png.getRGB(5, 5))
        assertTrue(Files.size(output) > 100)
    }

    @Test fun renderGallery() {
        val output = Path.of(System.getProperty("kainos.skinGalleryDir"))
        val cases = galleryCases()
        val manifest = mutableListOf("proof/swatch-row.png\t180\t40")
        for (skin in Skin.entries) for (case in cases) {
            val directory = output.resolve("${skin.name.lowercase()}-${case.id}")
            for (entry in galleryEntries) {
                val png = directory.resolve("${entry.id}.png")
                renderSkinPng(png, entry.width, entry.height, case.palette, skinTokens(skin), entry.content)
                val image = ImageIO.read(png.toFile())
                assertEquals(entry.width, image.width)
                assertEquals(entry.height, image.height)
                assertTrue(Files.size(png) > 1_000, "$png is empty or incomplete")
                val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
                when (entry.id) {
                    "typography" -> assertTrue(pixels.count { it == case.palette.scheme.onBackground.toArgb() } > 500,
                        "$png must show readable ink on the theme ground")
                    "accents" -> assertTrue(pixels.count { it == case.palette.accents.red.toArgb() } > 1_000,
                        "$png must show the Decor swatches")
                    "material" -> assertTrue(pixels.count { it == case.palette.scheme.primary.toArgb() } > 500,
                        "$png must show the themed button and slider fills")
                }
                manifest += "${directory.fileName}/${entry.id}.png\t${entry.width}\t${entry.height}"
            }
        }
        for (case in cases) {
            val folio = Files.readAllBytes(output.resolve("folio-${case.id}/typography.png"))
            val caelestia = Files.readAllBytes(output.resolve("caelestia-${case.id}/typography.png"))
            assertFalse(folio.contentEquals(caelestia), "The two skins must render different typography")
        }
        Files.writeString(output.resolve("manifest.tsv"), "file\twidth\theight\n" + manifest.sorted().joinToString("\n") + "\n")
    }
}

internal fun renderSkinPng(
    output: Path,
    width: Int,
    height: Int,
    palette: KainosPalette,
    tokens: SkinTokens,
    content: @Composable () -> Unit,
) {
    val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
        KainosTheme(palette, tokens) {
            Surface(Modifier.fillMaxSize(), color = palette.ground, contentColor = palette.scheme.onBackground) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }
    try {
        scene.render(0).close()
        scene.render(1_000_000_000L).use { image ->
            Files.createDirectories(output.parent)
            image.encodeToData().use { data -> Files.write(output, checkNotNull(data).bytes) }
        }
    } finally {
        scene.close()
    }
}

internal data class GalleryEntry(
    val id: String,
    val width: Int = 1040,
    val height: Int = 1000,
    val content: @Composable () -> Unit,
)

internal val galleryEntries = listOf(
    GalleryEntry("accents") { AccentSpecimens() },
    GalleryEntry("typography", height = 1200) { TypographySpecimens() },
    GalleryEntry("material", height = 800) { MaterialSpecimens() },
    GalleryEntry("seek", height = 760) { SeekSpecimens() },
    GalleryEntry("marks", width = 1200, height = 640) { MarkSpecimens() },
    GalleryEntry("rows", height = 1000) { RowSpecimens() },
)

private data class GalleryCase(val id: String, val palette: KainosPalette)

private fun galleryCases() = listOf(
    GalleryCase("folio-light", folioPalette(FolioTint.FOLIO, false)),
    GalleryCase("folio-dark", folioPalette(FolioTint.FOLIO, true)),
    GalleryCase("catppuccin-dark", folioPalette(FolioTint.CATPPUCCIN, true)),
    GalleryCase("live-scheme-dark", checkNotNull(parseCaelestiaScheme(schemeFixture())).palette),
)

@Composable
private fun SpecimenPage(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        content()
    }
}

@Composable
private fun AccentSpecimens() {
    val palette = LocalKainosPalette.current
    SpecimenPage("Accent ramp") {
        listOf("Ground" to palette.ground, "Sheet" to palette.sheet, "Tile" to palette.tile).forEach { (name, on) ->
            Column(Modifier.fillMaxWidth().background(on).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Row {
                    Box(Modifier.width(72.dp))
                    AccentSlot.entries.forEach { slot ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(slot.name, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                AccentUse.entries.forEach { use ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(use.name, Modifier.width(72.dp), style = MaterialTheme.typography.labelSmall)
                        AccentSlot.entries.forEach { slot ->
                            Box(Modifier.weight(1f).size(48.dp), contentAlignment = Alignment.Center) {
                                val color = palette.accent(slot, use, on)
                                when (use) {
                                    AccentUse.Decor -> Box(Modifier.size(36.dp).background(color, MaterialTheme.shapes.small))
                                    AccentUse.Mark -> Box(Modifier.size(20.dp).background(color, LocalSkinTokens.current.rounding.full))
                                    AccentUse.Text -> Text("Aa", color = color, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TypographySpecimens() {
    val type = MaterialTheme.typography
    val specimens = listOf(
        "Display large" to type.displayLarge, "Display medium" to type.displayMedium, "Display small" to type.displaySmall,
        "Headline large" to type.headlineLarge, "Headline medium" to type.headlineMedium, "Headline small" to type.headlineSmall,
        "Title large" to type.titleLarge, "Title medium" to type.titleMedium, "Title small" to type.titleSmall,
        "Body large" to type.bodyLarge, "Body medium" to type.bodyMedium, "Body small" to type.bodySmall,
        "Label large" to type.labelLarge, "Label medium" to type.labelMedium, "Label small" to type.labelSmall,
    )
    SpecimenPage("Typography") {
        specimens.forEach { (name, style) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.width(144.dp), style = type.labelSmall)
                Text("Saltwater Cathedral", style = style)
            }
        }
        Text("2:14 / 5:48", style = TextStyle(fontFamily = LocalSkinTokens.current.display, fontSize = type.headlineLarge.fontSize))
    }
}

@Composable
private fun MaterialSpecimens() {
    SpecimenPage("Material components") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {}) { Text("Play all") }
            FilledTonalButton(onClick = {}) { Text("Play favorites") }
            Button(onClick = {}, enabled = false) { Text("Unavailable") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Shuffle")
            Switch(checked = true, onCheckedChange = {})
            Switch(checked = false, onCheckedChange = {})
            Switch(checked = true, onCheckedChange = null, enabled = false)
        }
        Slider(value = .42f, onValueChange = {}, modifier = Modifier.width(600.dp))
        OutlinedTextField(value = "Mira Okonkwo", onValueChange = {}, label = { Text("Library search") }, modifier = Modifier.width(600.dp))
        OutlinedTextField(value = "Unavailable folder", onValueChange = {}, isError = true,
            label = { Text("Music folder") }, supportingText = { Text("Choose a folder") }, modifier = Modifier.width(600.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(selected = true, onClick = {}, label = { Text("Favorites only") })
            FilterChip(selected = false, onClick = {}, label = { Text("Local files only") })
            AssistChip(onClick = {}, label = { Text("Cached") })
        }
    }
}
