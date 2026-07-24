plugins {
  id("harc.base-conventions")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "ee.schimke.ha.rc.cards.shutter"
  compileSdk = libs.versions.android.compileSdk.get().toInt()
  defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
  buildFeatures { compose = true }
  compileOptions {
    sourceCompatibility = JavaVersion.toVersion(libs.versions.javaTarget.get())
    targetCompatibility = JavaVersion.toVersion(libs.versions.javaTarget.get())
  }
  kotlin { jvmToolchain(libs.versions.java.get().toInt()) }
}

dependencies {
  api(project(":rc-converter"))
  api(project(":rc-components"))
  implementation(project(":ha-model"))

  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  // Tooling-preview annotation jar — `compose-preview render` (0.12.0)
  // fails fast on any Android+compose module that can't reach the
  // @Preview annotation class, even when the module has no @Preview
  // composables of its own.
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.compose.foundation)
  implementation(libs.compose.material3)
  implementation(libs.compose.material.icons.extended)

  implementation(libs.remote.creation.compose)
  implementation(libs.remote.creation)
  implementation(libs.remote.creation.core)
  implementation(libs.remote.core)
  implementation(libs.remote.material3)

  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.kotlin.test)
}
