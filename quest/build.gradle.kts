plugins {
  id("harc.base-conventions")
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

// The room controls on Meta Quest, on Meta's Spatial SDK. Everything but the spatial layer is
// shared with the phone / Android XR app: the room model (`:ha-model`) and the room screen, pins
// and placement (`:room-ui`). This module implements `RoomAnchors` on MRUK and hosts the room's
// Compose UI in Spatial SDK panels. (`:terrazzo-core` needs API 35; Quest 3 runs API 34.)
android {
  namespace = "ee.schimke.terrazzo.quest"
  // Horizon OS currently supports API 34 for immersive Quest apps. Keep this module pinned
  // instead of inheriting the newer phone/Android-XR SDK level from the version catalog.
  compileSdk = 34
  defaultConfig {
    applicationId = "ee.schimke.terrazzo.quest"
    // The Spatial SDK's Compose and UI set need 28; the shared modules need the repo's floor.
    minSdk = libs.versions.android.minSdk.get().toInt()
    targetSdk = 34
    versionCode = 1
    versionName = "0.1.0"
  }
  buildFeatures { compose = true }
  compileOptions {
    sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
  }
  kotlin { jvmToolchain(libs.versions.java.get().toInt()) }
  testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
  implementation(project(":room-ui"))
  implementation(project(":ha-model"))
  implementation(project(":ha-client"))

  implementation(libs.meta.spatial.sdk)
  implementation(libs.meta.spatial.sdk.compose)
  implementation(libs.meta.spatial.sdk.toolkit)
  implementation(libs.meta.spatial.sdk.vr)
  implementation(libs.meta.spatial.sdk.mruk)
  implementation(libs.meta.spatial.sdk.uiset)

  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  implementation(libs.compose.foundation)
  implementation(libs.compose.material3)
  implementation(libs.compose.ui.tooling.preview)
  debugImplementation(libs.compose.ui.tooling)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.appauth)

  testImplementation(libs.kotlin.test.junit)
}
