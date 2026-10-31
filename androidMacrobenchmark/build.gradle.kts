plugins { id("com.android.test") }
android {
    namespace = "com.universalmusic.player.benchmark"
    compileSdk = 36
    targetProjectPath = ":androidApp"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    defaultConfig {
        minSdk = 33
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR,DEBUGGABLE"
    }
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            // AGP requires a mapping pass when the target uses R8. Keep the
            // driver intact so every target uses the same test behavior.
            isMinifyEnabled = true
            testProguardFiles("test-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf(providers.gradleProperty("benchmarkTarget").getOrElse("optimized"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
androidComponents { beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" } }
dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
