plugins {
  id("harc.base-conventions")
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

// The room controls on Android XR AI glasses. A phone app, like every glasses app: its launcher
// runs
// on the phone and starts `GlassesRoomActivity` on the glasses' display through `:projected`, where
// the room is drawn with Jetpack Compose Glimmer. The room model and the phone screen are shared
// (`:ha-model`, `:room-ui`). Its own module because Glimmer pulls Compose to a 1.13 alpha, past the
// main app's BOM.
android {
  namespace = "ee.schimke.terrazzo.glasses"
  compileSdk {
    version = release(libs.versions.android.compileSdk.get().toInt()) { minorApiLevel = 1 }
  }
  defaultConfig {
    applicationId = "ee.schimke.terrazzo.glasses"
    minSdk = libs.versions.android.minSdk.get().toInt()
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
  testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
  implementation(project(":room-ui"))
  implementation(project(":ha-model"))

  implementation(libs.xr.glimmer)
  implementation(libs.xr.projected)

  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  implementation(libs.compose.foundation)
  implementation(libs.compose.material3)
  implementation(libs.compose.ui.tooling.preview)
  debugImplementation(libs.compose.ui.tooling)
  implementation(libs.activity.compose)
  implementation(libs.kotlinx.coroutines.core)

  testImplementation(libs.kotlin.test.junit)
}
