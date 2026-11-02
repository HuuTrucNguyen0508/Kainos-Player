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

// Opting into native gapless stress must invalidate a cached non-stress test run.
tasks.named<Test>("jvmTest") {
    val stress = providers.environmentVariable("KAINOS_GAPLESS_STRESS").getOrElse("0")
    inputs.property("gaplessStress", stress)
    environment("KAINOS_GAPLESS_STRESS", stress)
}
