plugins {
    id("kainos.kmp.library")
}

kotlin {
    androidLibrary {
        withHostTestBuilder {}.configure { isIncludeAndroidResources = true }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:data"))
            api(libs.ktor.client.core)
            api(libs.ktor.client.content.negotiation)
            api(libs.ktor.serialization.kotlinx.json)
            implementation(project(":provider:local"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.documentfile)
            implementation(libs.androidx.work.runtime.ktx)
            implementation(libs.ktor.client.okhttp)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.netty)
            implementation(libs.ktor.network.tls.certificates)
            implementation(libs.dbus.java.core)
            implementation(libs.dbus.java.transport.native.unixsocket)
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.androidx.work.testing)
            implementation(libs.androidx.test.core)
            implementation(libs.robolectric)
            implementation(libs.junit)
        }
    }
}

tasks.named<Test>("jvmTest") {
    val avahi = providers.systemProperty("kainos.avahi").orNull == "true"
    systemProperty("kainos.avahi", avahi.toString())
    testLogging.showStandardStreams = avahi
}
