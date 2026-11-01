fun git(vararg args: String) = providers.exec {
    commandLine("git", *args)
    isIgnoreExitValue = true
}.standardOutput.asText.map(String::trim)

// A shallow clone counts only its fetched commits, so anything shipped from one
// must pass -PkainosVersionCode. The gate rejects a versionCode of 1.
val code = providers.gradleProperty("kainosVersionCode")
    .orElse(git("rev-list", "--count", "HEAD"))
    .map { it.toIntOrNull()?.coerceAtLeast(1) ?: 1 }
// Commit date, not build date: two builds of one commit report the same version.
val name = providers.gradleProperty("kainosVersionName")
    .orElse(git("log", "-1", "--format=%cs"))
    .map { it.ifBlank { "dev" } }

extensions.add("kainosVersion", KainosVersion(code.get(), name.get()))
