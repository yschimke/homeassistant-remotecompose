plugins {
  id("harc.base-conventions")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "ee.schimke.ha.catalog"
  compileSdk {
    version = release(libs.versions.android.compileSdk.get().toInt()) { minorApiLevel = 1 }
  }
  defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
  kotlin { jvmToolchain(21) }
  testOptions { unitTests.isIncludeAndroidResources = true }
}

val robolectricSdk by configurations.creating {
  isCanBeConsumed = false
  isTransitive = false
}
val prepareRobolectricSdk by
  tasks.registering(Sync::class) {
    from(robolectricSdk)
    into(layout.buildDirectory.dir("robolectric-sdk"))
  }

dependencies {
  // Matches Robolectric 4.17's SDK 35 provider; resolve with Gradle, not a hidden test download.
  robolectricSdk("org.robolectric:android-all-instrumented:15-robolectric-13954326-i7")
  implementation(project(":rc-components-ui"))
  // SDK bytecode is portable Compose; use this app's Android Compose runtime, not Desktop jars.
  implementation("ee.schimke.composeai:ui-builder-renderer-sdk:0.0.0") {
    exclude(group = "org.jetbrains.compose.runtime")
    exclude(group = "org.jetbrains.compose.foundation")
    exclude(group = "org.jetbrains.compose.ui")
    exclude(group = "org.jetbrains.compose.material")
  }
  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  implementation(libs.compose.foundation)
  implementation(libs.compose.material3)
  implementation(libs.kotlinx.serialization.json)
  testImplementation("ee.schimke.composeai:preview-discovery:0.0.0")
  testImplementation(libs.kotlin.test.junit)
  testImplementation("org.robolectric:robolectric:4.17")
  testImplementation(libs.compose.ui.test.junit4)
  debugImplementation(libs.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach {
  outputs.dir(layout.buildDirectory.dir("renders/$name"))
  inputs.files(robolectricSdk)
  systemProperty(
    "typedCatalogRenders",
    layout.buildDirectory.dir("renders/$name").get().asFile.absolutePath,
  )
  dependsOn(prepareRobolectricSdk)
  systemProperty("robolectric.offline", "true")
  systemProperty(
    "robolectric.dependency.dir",
    layout.buildDirectory.dir("robolectric-sdk").get().asFile.absolutePath,
  )
  val output = layout.buildDirectory.dir("catalog/$name")
  systemProperty("typedCatalogOutput", output.get().asFile.absolutePath)
  outputs.dir(output)
}

// The metadata export runs in the existing native test host, on fresh compiled library classes.
tasks.register("exportTypedCatalog") {
  group = "ui builder"
  description = "Generate the opted-in native Home Assistant component catalog."
  dependsOn("testDebugUnitTest")
}
