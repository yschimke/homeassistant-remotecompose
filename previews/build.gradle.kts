plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.preview)
}

android {
    namespace = "ee.schimke.ha.previews"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    }
    kotlin { jvmToolchain(libs.versions.java.get().toInt()) }
}

composePreview {
    variant.set("debug")
    sdkVersion.set(35)
    enabled.set(true)
}

dependencies {
    implementation(project(":ha-model"))
    implementation(project(":rc-converter"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.remote.creation.compose)
    implementation(libs.remote.player.compose)
    implementation(libs.remote.player.view)
    implementation(libs.remote.tooling.preview)

    implementation(libs.kotlinx.serialization.json)
}
