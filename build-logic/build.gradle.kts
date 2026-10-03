plugins { `kotlin-dsl` }
val catalogText = file("../gradle/libs.versions.toml").readText()
fun version(name: String) = Regex("(?m)^${Regex.escape(name)} = \"([^\"]+)\"").find(catalogText)!!.groupValues[1]
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${version("kotlin")}")
    implementation("org.jetbrains.kotlin:kotlin-serialization:${version("kotlin")}")
    implementation("com.android.tools.build:gradle:${version("agp")}")
}
