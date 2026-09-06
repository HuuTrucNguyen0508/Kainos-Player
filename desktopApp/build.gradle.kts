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
            packageName = "Kainos Player"
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
