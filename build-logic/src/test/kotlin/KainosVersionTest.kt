import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KainosVersionTest {
    @Test fun anOlderSchemaBuiltLaterStillHasTheLowerCode() {
        // 2026-11-07: a schema-8 branch with more commits replaced a schema-9 install and crash-looped.
        val newerSchema = kainosVersionCode(schemaVersion = 9, commitCount = 1_480)
        val olderSchemaMoreCommits = kainosVersionCode(schemaVersion = 8, commitCount = 1_530)
        assertTrue(olderSchemaMoreCommits < newerSchema)
    }

    @Test fun theFirstSchemaEncodedCodeUpgradesTheCommitCountCodesAlreadyInstalled() {
        assertTrue(kainosVersionCode(schemaVersion = 1, commitCount = 1) > 9_999)
        assertEquals(901_500, kainosVersionCode(schemaVersion = 9, commitCount = 1_500))
    }

    @Test fun codesStayInsidePlaysCeiling() {
        assertTrue(kainosVersionCode(20_999, SCHEMA_STEP - 1) <= 2_100_000_000)
        assertFailsWith<IllegalArgumentException> { kainosVersionCode(21_000, 1) }
        assertFailsWith<IllegalArgumentException> { kainosVersionCode(9, SCHEMA_STEP) }
    }

    @Test fun schemaVersionIsOneAboveTheHighestMigration() {
        assertEquals(9, sqldelightSchemaVersion(listOf("1.sqm", "8.sqm", "3.sqm", "notes.md")))
        assertEquals(1, sqldelightSchemaVersion(emptyList()))
    }
}
