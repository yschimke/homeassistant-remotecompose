plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.preview)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ee.schimke.terrazzo.wear"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "ee.schimke.terrazzo.wear"
        minSdk = 30
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
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
    sdkVersion.set(34)
    enabled.set(true)
}

dependencies {
    implementation(project(":rc-components"))
    implementation(project(":ha-model"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    // `rc-components` exposes terrazzoColorScheme returning an androidx.compose.material3 ColorScheme;
    // we include material3 here only to unpack that for the Wear `ColorScheme.copy(...)` mapping.
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.text.google.fonts)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)

    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)

    // RemoteCompose player — wear renders the `.rc` bytes phone bakes
    // and pushes via the data layer. The phone-side capture/converter
    // never runs on the watch; we just decode and play.
    implementation(libs.remote.core)
    implementation(libs.remote.player.compose)

    // Proto DataStore + Horologist data layer for sync with phone.
    // Schema is documented in `src/main/proto/wear_sync.proto`; we
    // encode @Serializable Kotlin data classes to the same proto wire
    // format via kotlinx-serialization-protobuf. (TODO: switch to Wire
    // once its Gradle plugin recognises AGP 9's built-in Kotlin.)
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.play.services.wearable)
    implementation(libs.horologist.datalayer)
    implementation(libs.horologist.datalayer.watch)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
