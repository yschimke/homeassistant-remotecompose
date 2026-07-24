plugins {
  id("harc.base-conventions")
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "ee.schimke.ha.demo"
  compileSdk = libs.versions.android.compileSdk.get().toInt()
  defaultConfig {
    applicationId = "ee.schimke.ha.demo"
    minSdk = libs.versions.android.minSdk.get().toInt()
    targetSdk = libs.versions.android.targetSdk.get().toInt()
    versionCode = 1
    versionName = "0.1.0"
  }
  buildFeatures { compose = true }
  compileOptions {
    sourceCompatibility = JavaVersion.toVersion(libs.versions.javaTarget.get())
    targetCompatibility = JavaVersion.toVersion(libs.versions.javaTarget.get())
  }
  kotlin { jvmToolchain(libs.versions.java.get().toInt()) }
}

dependencies {
  implementation(project(":ha-model"))
  implementation(project(":ha-client"))
  implementation(project(":rc-converter"))
  implementation(project(":rc-card-shutter"))

  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  implementation(libs.compose.foundation)
  implementation(libs.compose.material3)
  implementation(libs.activity.compose)
  // No @Preview composables in :demo-app, but the compose-preview CLI's
  // 0.12.0 module auto-detection treats "annotation class not on the
  // ClassGraph classpath" as a hard error (rc=2) instead of a silent
  // "0 previews here, skip", aborting the workflow before the modules
  // that do contribute baselines are rendered. The annotation-only
  // artifact is tiny and ships no renderer, so it stays out of the
  // release APK while satisfying discovery.
  implementation(libs.compose.ui.tooling.preview)

  implementation(libs.remote.player.compose)
  implementation(libs.remote.player.view)
}
