fun git(vararg args: String) = providers.exec {
    commandLine("git", *args)
    isIgnoreExitValue = true
}.standardOutput.asText.map(String::trim)

// -PkainosVersionCode replaces the commit count, the low part of the code.
// A shallow clone counts only its fetched commits, which would repeat or lower a shipped code.
val explicitCode = providers.gradleProperty("kainosVersionCode")
if (!explicitCode.isPresent && git("rev-parse", "--is-shallow-repository").get() == "true") {
    error("This clone is shallow, so commit count is not a version. Pass -PkainosVersionCode=<n>.")
}
val commitCount = explicitCode
    .orElse(git("rev-list", "--count", "HEAD"))
    .map { it.toIntOrNull()?.coerceAtLeast(1) ?: 1 }
val migrations = rootDir.resolve("core/data/src/commonMain/sqldelight/com/universalmusic/player/data/db")
val schemaVersion = sqldelightSchemaVersion(migrations.list()?.toList().orEmpty())
check(schemaVersion > 1) { "No SQLDelight migrations found in $migrations" }
val code = commitCount.map { kainosVersionCode(schemaVersion, it) }
// Commit date, not build date: two builds of one commit report the same version.
val name = providers.gradleProperty("kainosVersionName")
    .orElse(git("log", "-1", "--format=%cs"))
    .map { it.ifBlank { "dev" } }

extensions.add("kainosVersion", KainosVersion(code.get(), name.get()))
