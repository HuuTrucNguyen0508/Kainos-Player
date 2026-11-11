/** [name] lines up with the `linux-android-<name>` release tag. */
data class KainosVersion(val code: Int, val name: String)

/**
 * The database schema is the high part of the Android version code, the build's commit count
 * the low part. A build whose schema is older than the installed one then has a lower code, and
 * Android refuses it as a downgrade instead of installing it into a "Can't downgrade database"
 * crash loop, whatever branch either was built from.
 */
fun kainosVersionCode(schemaVersion: Int, commitCount: Int): Int {
    require(schemaVersion in 1..MAX_SCHEMA_VERSION) { "Schema version $schemaVersion does not fit the version code" }
    require(commitCount in 1 until SCHEMA_STEP) { "Commit count $commitCount does not fit below one schema step ($SCHEMA_STEP)" }
    return schemaVersion * SCHEMA_STEP + commitCount
}

/** SQLDelight's schema version: one more than the highest numbered `.sqm` migration. */
fun sqldelightSchemaVersion(migrationFileNames: Collection<String>): Int =
    (migrationFileNames.mapNotNull { Regex("""(\d+)\.sqm""").matchEntire(it)?.groupValues?.get(1)?.toInt() }.maxOrNull() ?: 0) + 1

const val SCHEMA_STEP = 100_000
/** Google Play's ceiling is 2_100_000_000; stay under it. */
private const val MAX_SCHEMA_VERSION = 2_100_000_000 / SCHEMA_STEP - 1
