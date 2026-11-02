plugins {
    id("kainos.kmp.library")
}

kotlin {
    sourceSets {
        androidMain { kotlin.srcDir("src/jvmAndroidMain/kotlin") }
        jvmMain { kotlin.srcDir("src/jvmAndroidMain/kotlin") }
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:data"))
            api(libs.ktor.client.core)
            api(libs.ktor.client.content.negotiation)
            api(libs.ktor.serialization.kotlinx.json)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.librespot.android.sink)
            implementation(libs.librespot.android.decoder)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
        }
    }
}
