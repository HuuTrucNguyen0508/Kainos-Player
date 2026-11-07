import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
}

kotlin {
    jvm()

    androidLibrary {
        namespace = "com.universalmusic.player.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
    }

    sourceSets {
        commonTest { kotlin.srcDir(rootProject.file("test-fixtures/kotlin")) }
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:data"))
            api(project(":core:playback"))
            api(project(":provider:local"))
            api(project(":provider:spotify"))
            api(project(":provider:youtube"))
            api(project(":sync"))

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.material.icons.core)
            implementation(libs.compose.material.icons.extended)

            implementation(libs.navigation.compose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.androidx.lifecycle.viewmodelCompose)


            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(compose.desktop.uiTestJUnit4)
        }
        jvmMain.dependencies {
            implementation(libs.zxing.core)
        }
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
            implementation(libs.zxing.core)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}

val jvmTests = tasks.named<Test>("jvmTest") {
    exclude("**/SkinGalleryTest*")
    // Tests must not pick up the host shell's Frost wallpaper as the skin.
    environment("KAINOS_WALLPAPER_PATH_FILE", layout.buildDirectory.file("no-wallpaper/path.txt").get().asFile.absolutePath)
}

tasks.register<Test>("skinGallery") {
    group = "verification"
    description = "Render the skin component gallery as PNGs."
    dependsOn("jvmTestClasses")
    testClassesDirs = jvmTests.get().testClassesDirs
    classpath = jvmTests.get().classpath
    filter { includeTestsMatching("*.SkinGalleryTest") }
    val galleryDir = layout.buildDirectory.dir("skin-gallery")
    systemProperty("kainos.skinGalleryDir", galleryDir.get().asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
    doFirst {
        val directory = galleryDir.get().asFile
        check(!directory.exists() || directory.deleteRecursively()) { "Could not clear the old skin gallery" }
    }
    outputs.dir(galleryDir)
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Gallery uses the host system fonts") { true }
}
