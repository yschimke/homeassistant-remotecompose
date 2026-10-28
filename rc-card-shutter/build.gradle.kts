plugins {
  id("harc.base-conventions")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "ee.schimke.ha.rc.cards.shutter"
  compileSdk {
    version = release(libs.versions.android.compileSdk.get().toInt()) { minorApiLevel = 1 }
  }
  defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
  buildFeatures { compose = true }
  compileOptions {
    sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
  }
  kotlin { jvmToolchain(libs.versions.java.get().toInt()) }
  testOptions { unitTests.isIncludeAndroidResources = true }
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
  testImplementation(libs.kotlin.test.junit)
  testImplementation("org.robolectric:robolectric:4.17")
  testImplementation(platform(libs.compose.bom))
  testImplementation(libs.compose.ui.test.junit4)
  debugImplementation(libs.compose.ui.test.manifest)
}

// Robolectric's SDK jar, resolved by Gradle (offline at test time) — same setup as
// `:rc-converter`. The cover tests capture and play real documents.
val robolectricSdk by configurations.creating {
  isCanBeConsumed = false
  isTransitive = false
}
val prepareRobolectricSdk by
  tasks.registering(Sync::class) {
    from(robolectricSdk)
    into(layout.buildDirectory.dir("robolectric-sdk"))
  }

tasks.withType<Test>().configureEach {
  inputs.files(robolectricSdk)
  dependsOn(prepareRobolectricSdk)
  systemProperty("robolectric.offline", "true")
  systemProperty(
    "robolectric.dependency.dir",
    layout.buildDirectory.dir("robolectric-sdk").get().asFile.absolutePath,
  )
}

dependencies { robolectricSdk(libs.robolectric.sdk35) }
