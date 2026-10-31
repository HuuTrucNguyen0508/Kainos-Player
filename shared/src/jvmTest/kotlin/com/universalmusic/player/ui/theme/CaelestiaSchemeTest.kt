package com.universalmusic.player.ui.theme

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaelestiaSchemeTest {
    private fun change(key: String, value: String?): String {
        val root = Json.parseToJsonElement(schemeFixture()).jsonObject.toMutableMap()
        val colors = root.getValue("colours").jsonObject.toMutableMap()
        if (value == null) colors.remove(key) else colors[key] = JsonPrimitive(value)
        root["colours"] = JsonObject(colors)
        return JsonObject(root).toString()
    }

    @Test fun realShapeMapsEveryRoleAndAccent() {
        val parsed = assertNotNull(parseCaelestiaScheme(schemeFixture()))
        assertTrue(parsed.isDark)
        val scheme = parsed.palette.scheme
        val expected = Json.parseToJsonElement(schemeFixture()).jsonObject.getValue("colours").jsonObject
        val roles = "primary onPrimary primaryContainer onPrimaryContainer inversePrimary secondary onSecondary secondaryContainer onSecondaryContainer tertiary onTertiary tertiaryContainer onTertiaryContainer error onError errorContainer onErrorContainer background onBackground surface onSurface surfaceVariant onSurfaceVariant surfaceTint inverseSurface inverseOnSurface outline outlineVariant scrim surfaceBright surfaceDim surfaceContainerLowest surfaceContainerLow surfaceContainer surfaceContainerHigh surfaceContainerHighest primaryFixed primaryFixedDim secondaryFixed secondaryFixedDim tertiaryFixed tertiaryFixedDim onPrimaryFixed onPrimaryFixedVariant onSecondaryFixed onSecondaryFixedVariant onTertiaryFixed onTertiaryFixedVariant".split(" ")
        val actual = listOf(scheme.primary, scheme.onPrimary, scheme.primaryContainer, scheme.onPrimaryContainer, scheme.inversePrimary,
            scheme.secondary, scheme.onSecondary, scheme.secondaryContainer, scheme.onSecondaryContainer,
            scheme.tertiary, scheme.onTertiary, scheme.tertiaryContainer, scheme.onTertiaryContainer,
            scheme.error, scheme.onError, scheme.errorContainer, scheme.onErrorContainer,
            scheme.background, scheme.onBackground, scheme.surface, scheme.onSurface, scheme.surfaceVariant, scheme.onSurfaceVariant,
            scheme.surfaceTint, scheme.inverseSurface, scheme.inverseOnSurface, scheme.outline, scheme.outlineVariant, scheme.scrim,
            scheme.surfaceBright, scheme.surfaceDim, scheme.surfaceContainerLowest, scheme.surfaceContainerLow, scheme.surfaceContainer,
            scheme.surfaceContainerHigh, scheme.surfaceContainerHighest,
            scheme.primaryFixed, scheme.primaryFixedDim, scheme.secondaryFixed, scheme.secondaryFixedDim, scheme.tertiaryFixed, scheme.tertiaryFixedDim,
            scheme.onPrimaryFixed, scheme.onPrimaryFixedVariant, scheme.onSecondaryFixed, scheme.onSecondaryFixedVariant,
            scheme.onTertiaryFixed, scheme.onTertiaryFixedVariant)
        roles.zip(actual).forEach { (key, color) ->
            assertEquals(Color(0xFF000000L or (expected.getValue(key) as JsonPrimitive).content.toLong(16)), color, key)
        }
        assertEquals(Color(0xFFE69AE2), parsed.palette.accents.red)
        assertEquals(Color(0xFFF9BCE3), parsed.palette.accents.orange)
        assertEquals(Color(0xFFD6BBFF), parsed.palette.accents.blue)
        assertEquals(Color(0xFF5D545D), parsed.palette.accents.gray)
        assertEquals(scheme.background, parsed.palette.ground)
        assertEquals(scheme.surfaceContainerLow, parsed.palette.sheet)
        assertEquals(scheme.surfaceContainer, parsed.palette.tile)
    }

    @Test fun consumedKeysAreRequiredAndStrict() {
        for (key in listOf("primary", "onSurface", "surfaceTint", "surfaceContainerLowest", "red", "peach", "overlay1")) {
            assertNull(parseCaelestiaScheme(change(key, null)), key)
            for (bad in listOf("#abcdef", "abc", "xyzxyz", "abcdef00", " abcde")) {
                assertNull(parseCaelestiaScheme(change(key, bad)), "$key $bad")
            }
        }
        assertNull(parseCaelestiaScheme(schemeFixture().replace("\"dark\"", "\"system\"")))
        assertNull(parseCaelestiaScheme("not json"))
        assertNull(parseCaelestiaScheme("[]"))
    }

    @Test fun unusedMalformedKeysAreIgnoredAndSapphireFallsBackToBlueOnlyWhenAbsent() {
        assertNotNull(parseCaelestiaScheme(change("term0", "broken")))
        assertNotNull(parseCaelestiaScheme(change("primaryDim", "broken")))
        assertEquals(Color(0xFFD6ABFC), assertNotNull(parseCaelestiaScheme(change("sapphire", null))).palette.accents.blue)
        assertNull(parseCaelestiaScheme(change("sapphire", "broken")))
    }
}
