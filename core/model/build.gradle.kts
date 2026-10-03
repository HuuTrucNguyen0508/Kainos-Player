plugins {
    id("kainos.kmp.library")
}

kotlin {
    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.core)
        }
    }
}
