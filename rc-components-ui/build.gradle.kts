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
  implementation(libs.compose.foundation)

  implementation(libs.remote.creation.compose)
  implementation(libs.remote.creation.android)
  implementation(libs.remote.creation.core)
  implementation(libs.remote.core)
  implementation(libs.remote.tooling.preview)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlin.test.junit)
  testImplementation(libs.compose.material.icons.extended)
}
