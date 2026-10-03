plugins {
    id("kainos.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.documentfile)
        }
    }
}
