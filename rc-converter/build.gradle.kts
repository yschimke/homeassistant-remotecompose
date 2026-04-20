plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "ee.schimke.ha.rc"
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

dependencies {
    implementation(project(":ha-model"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    // RemoteCompose authoring + playback
    implementation(libs.remote.creation.compose)
    implementation(libs.remote.creation.android)
    implementation(libs.remote.creation.core)
    implementation(libs.remote.core)
    implementation(libs.remote.player.core)
    implementation(libs.remote.player.compose)

    testImplementation(libs.kotlin.test)
}
