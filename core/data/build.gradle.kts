plugins {
    id("kainos.kmp.library")
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("KainosDatabase") {
            packageName.set("com.universalmusic.player.data.db")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}

kotlin {
    sourceSets {
        getByName("jvmTest").resources.srcDir("src/commonMain/sqldelight/databases")
        commonMain.dependencies {
            api(project(":core:model"))
            api(libs.ktor.client.core)
            api(libs.ktor.client.content.negotiation)
            api(libs.ktor.serialization.kotlinx.json)
            api(libs.sqldelight.runtime)
            api(libs.sqldelight.coroutines)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android.driver)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}
