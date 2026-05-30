plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "ee.schimke.ha.rc.ui"
  compileSdk = libs.versions.android.compileSdk.get().toInt()
  defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
  buildFeatures { compose = true }
  compileOptions {
    sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
  }
  kotlin { jvmToolchain(libs.versions.java.get().toInt()) }
  // RemoteCompose's `RemoteInt` clinit calls `android.icu.text.DecimalFormat`,
  // which the default Android JVM unit-test classpath leaves unmocked.
  // The conversion smoke tests don't care about the formatting result, so
  // returning Android stubs as default values is enough.
  testOptions { unitTests { isReturnDefaultValues = true } }
}

dependencies {
  api(project(":rc-components"))

  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  // Tooling-preview annotation jar — `compose-preview render` (0.12.0)
  // fails fast on any Android+compose module that can't reach the
  // @Preview annotation class, even when the module has no @Preview
  // composables of its own.
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.compose.foundation)

  implementation(libs.remote.creation.compose)
  implementation(libs.remote.creation)
  implementation(libs.remote.creation.core)
  implementation(libs.remote.core)
  implementation(libs.remote.player.core)
  implementation(libs.remote.player.compose)
  implementation(libs.remote.tooling.preview)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlin.test.junit)
  testImplementation(libs.compose.material.icons.extended)
}
