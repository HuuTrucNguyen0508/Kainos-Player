plugins {
    id("kainos.kmp.library")
}

kotlin {
    sourceSets {
        commonTest.dependencies { implementation(project(":core:data")) }
        commonMain.dependencies {
            api(project(":core:model"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.documentfile)
        }
    }
}
