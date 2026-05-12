val wearVersionName = "0.1.20" // x-release-please-version

// Keep Wear version tied to release-please while reserving a unique code
// lane for the shared package id (`ee.schimke.harc`).
val wearVersionCode: Int =
  run {
      val parts = wearVersionName.split(".", "-").mapNotNull { it.toIntOrNull() }
      val major = parts.getOrNull(0) ?: 0
      val minor = parts.getOrNull(1) ?: 0
      val patch = parts.getOrNull(2) ?: 0
      (major * 10_000 + minor * 100 + patch) * 10 + 2
    }
    .coerceAtLeast(2)

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.compose.preview)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.play.publisher)
}

android {
  namespace = "ee.schimke.terrazzo.wear"
  compileSdk = libs.versions.android.compileSdk.get().toInt()
  defaultConfig {
    applicationId = "ee.schimke.harc"
    minSdk = 30
    targetSdk = libs.versions.android.targetSdk.get().toInt()
    versionCode = wearVersionCode
    versionName = wearVersionName
  }
  buildFeatures { compose = true }
  compileOptions {
    sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
  }
  kotlin { jvmToolchain(libs.versions.java.get().toInt()) }
  lint {
    // Compose Preview plugin generates preview parameter providers under
    // build/generated that can reference @RestrictTo APIs from Glance Wear.
    // These generated stubs are not shipped runtime code, so skip generated
    // source lint checks for this module.
    checkGeneratedSources = false
  }
}

composePreview {
  variant.set("debug")
  sdkVersion.set(34)
  enabled.set(true)
}

play {
  // First Wear rollout uses Play internal testing for watch-only QA.
  track.set("internal")
  defaultToAppBundles.set(true)
  releaseStatus.set(com.github.triplet.gradle.androidpublisher.ReleaseStatus.DRAFT)
  // Only publish when CI/local env provides a Play service-account json file path.
  enabled.set(System.getenv("ANDROID_PUBLISHER_CREDENTIALS") != null)
}

dependencies {
  implementation(project(":rc-components"))
  implementation(project(":rc-converter"))
  implementation(project(":rc-card-shutter"))
  implementation(project(":ha-model"))

  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  implementation(libs.compose.foundation)
  // `rc-components` exposes terrazzoColorScheme returning an androidx.compose.material3
  // ColorScheme;
  // we include material3 here only to unpack that for the Wear `ColorScheme.copy(...)` mapping.
  implementation(libs.compose.material3)
  implementation(libs.compose.ui.text.google.fonts)
  implementation(libs.compose.ui.tooling.preview)
  debugImplementation(libs.compose.ui.tooling)
  implementation(libs.activity.compose)

  implementation(libs.wear.compose.material3)
  implementation(libs.wear.compose.foundation)
  implementation(libs.wear.tooling.preview)

  // AndroidX Glance Wear — runtime widget rendering. Pulls
  // remote-creation-compose, lifecycle-service, etc. transitively.
  implementation(libs.glance.wear)
  implementation(libs.glance.wear.core)

  // Vendored AOSP preview utility for Glance Wear widgets needs
  // RemoteDocPreview from this artifact. Tracking removal via the
  // TODOs on WearWidgetPreview.kt / WearWidgetParamsProvider.kt — once
  // androidx.glance.wear:wear-tooling-preview ships a non-empty
  // implementation, drop the vendored copies (and re-evaluate keeping
  // this dep — it only ships RemoteDocPreview today).
  implementation(libs.remote.tooling.preview)

  // Proto DataStore + Horologist data layer for sync with phone.
  // Schema is documented in `src/main/proto/wear_sync.proto`; we
  // encode @Serializable Kotlin data classes to the same proto wire
  // format via kotlinx-serialization-protobuf. (TODO: switch to Wire
  // once its Gradle plugin recognises AGP 9's built-in Kotlin.)
  implementation(libs.androidx.datastore)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.kotlinx.serialization.protobuf)
  implementation(libs.play.services.wearable)
  implementation(libs.horologist.datalayer)
  implementation(libs.horologist.datalayer.watch)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.process)

  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
}
