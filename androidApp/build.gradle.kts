import java.io.StringReader
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    id("kainos.version")
}

dependencies {
    implementation(projects.shared)
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.compose.foundation)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    coreLibraryDesugaring(libs.android.desugar.jdk.libs.nio)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation(libs.kotlinx.coroutines.core)
}

// librespot needs protobuf-java (GeneratedMessageV3); NewPipe pulls protobuf-javalite — keep java only.
configurations.configureEach {
    exclude(group = "com.google.protobuf", module = "protobuf-javalite")
}

val signingFile = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("signing.properties"))
        .asText.orNull?.let { load(StringReader(it)) }
}
val signingValues = listOf(
    "storeFile" to "KAINOS_SIGNING_STORE_FILE",
    "storePassword" to "KAINOS_SIGNING_STORE_PASSWORD",
    "keyAlias" to "KAINOS_SIGNING_KEY_ALIAS",
    "keyPassword" to "KAINOS_SIGNING_KEY_PASSWORD",
).associate { (key, env) -> key to (providers.environmentVariable(env).orNull ?: signingFile.getProperty(key)) }
// A half-configured key must fail: falling back to debug would ship the wrong certificate.
val incompleteSigning = signingValues.filterValues { it == null }.keys
if (incompleteSigning.isNotEmpty() && incompleteSigning.size < signingValues.size) {
    throw GradleException("Release signing is missing $incompleteSigning; see androidApp/README.md")
}

android {
    namespace = "com.universalmusic.player"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.universalmusic.player"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = kainosVersion.code
        versionName = kainosVersion.name
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testBuildType = providers.gradleProperty("androidTestTarget").getOrElse("debug")
    if (incompleteSigning.isEmpty()) {
        signingConfigs.create("kainosRelease") {
            storeFile = rootProject.file(signingValues.getValue("storeFile")!!)
            storePassword = signingValues.getValue("storePassword")
            keyAlias = signingValues.getValue("keyAlias")
            keyPassword = signingValues.getValue("keyPassword")
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
        // Keeps the debug certificate until a release key is configured, because
        // Android refuses an update signed by an unrelated key and uninstalling
        // would delete the phone's library. See androidApp/README.md before adopting one.
        create("optimized") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            isDebuggable = false
            signingConfig = signingConfigs.findByName("kainosRelease") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (providers.gradleProperty("androidTestTarget").orNull == "optimized") {
                proguardFiles("optimized-test-rules.pro")
            }
        }
    }
    buildTypes.create("profile") {
        initWith(buildTypes.getByName("optimized"))
        isMinifyEnabled = false
        isShrinkResources = false
        matchingFallbacks += listOf("release")
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
