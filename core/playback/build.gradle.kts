plugins {
    id("kainos.kmp.library")
}

kotlin {
    androidLibrary {
        withHostTestBuilder {}.configure { isIncludeAndroidResources = true }
    }
    sourceSets {
        getByName("androidHostTest").dependencies {
            implementation(libs.androidx.test.core)
            implementation(libs.robolectric)
            implementation(libs.junit)
        }
        commonMain.dependencies {
            api(project(":core:model"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.session)
        }
        jvmMain.dependencies {
            implementation(libs.dbus.java.core)
            implementation(libs.dbus.java.transport.native.unixsocket)
        }
    }
}
