import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm()
    sourceSets {
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(projects.shared)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.universalmusic.player.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Dmg, TargetFormat.Msi)
            // JDK 17 AppCDS maps the archive but reloads application classes when this path contains spaces.
            packageName = "KainosPlayer"
            packageVersion = "1.0.0"
            description = "A unified Material music player for Spotify and YouTube Music."
            copyright = "Copyright 2026"
            linux {
                iconFile.set(project.file("icons/linux.png"))
                shortcut = true
            }
        }
    }
}

// Training is optional and isolated. Missing tools never prevent packaging.
tasks.matching { it.name == "createDistributable" }.configureEach {
    val trainerScript = rootProject.file("scripts/perf/desktop-cds.py")
    val trainerDirectory = rootProject.projectDir
    inputs.files(trainerScript, rootProject.file("scripts/perf/desktop-bench.py"))
    inputs.property("cdsXvfb", providers.environmentVariable("KAINOS_CDS_XVFB").orElse("Xvfb"))
    doLast {
        try {
            val trainer = ProcessBuilder("python3", trainerScript.absolutePath)
                .directory(trainerDirectory)
                .inheritIO()
                .start()
            if (trainer.waitFor() != 0) logger.warn("AppCDS trainer failed; normal startup remains available")
        } catch (error: java.io.IOException) {
            logger.warn("AppCDS trainer unavailable; normal startup remains available", error)
        }
    }
}
