plugins {
    alias(libs.plugins.android.library)
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
    api(project(":rc-components"))
    implementation(project(":ha-model"))
    implementation(project(":ha-client"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    // RemoteCompose authoring + playback
    implementation(libs.remote.creation.compose)
    implementation(libs.remote.creation.android)
    implementation(libs.remote.creation.core)
    implementation(libs.remote.core)
    implementation(libs.remote.player.core)
    implementation(libs.remote.player.compose)
    implementation(libs.remote.player.view)
    implementation(libs.remote.material3)

    testImplementation(libs.kotlin.test)
}
