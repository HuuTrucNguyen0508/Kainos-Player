import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import com.android.build.api.dsl.DeprecatedKotlinMultiplatformAndroidLibraryTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
extensions.configure<KotlinMultiplatformExtension> {
    jvm()
    (this as ExtensionAware).extensions.configure<DeprecatedKotlinMultiplatformAndroidLibraryTarget>("androidLibrary") {
        namespace = "com.universalmusic.player." + project.path.trim(':').replace(':', '.')
        compileSdk = catalog.findVersion("android-compileSdk").get().requiredVersion.toInt()
        minSdk = catalog.findVersion("android-minSdk").get().requiredVersion.toInt()
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
    }
    sourceSets {
        named("commonTest") { kotlin.srcDir(rootProject.file("test-fixtures/kotlin")) }
        named("commonMain") { dependencies {
            api(catalog.findLibrary("kotlinx-coroutines-core").get())
            api(catalog.findLibrary("kotlinx-serialization-json").get())
        } }
        named("commonTest") { dependencies {
            implementation(kotlin("test"))
            implementation(catalog.findLibrary("kotlinx-coroutines-test").get())
            implementation(catalog.findLibrary("ktor-client-mock").get())
        } }
    }
}
