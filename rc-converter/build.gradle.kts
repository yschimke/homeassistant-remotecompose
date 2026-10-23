plugins {
  id("harc.base-conventions")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "ee.schimke.ha.rc"
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
  // The checked-in card designs (card-designs/ui-builder/designs/<cardType>.uid) migrated card types
  // draw their layout from (`CardDesigns`), packaged as `designs/<cardType>.uid`: one source for
  // the app, the library, the design server and the scheduled import.
  sourceSets["main"].resources.srcDir(rootProject.layout.projectDirectory.dir("card-designs/ui-builder"))
}

// Robolectric's SDK jar, resolved by Gradle (offline at test time) — same setup as
// `:ui-builder-catalog`. Used by the card design template end-to-end test, which captures a real
// document.
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
  providers.gradleProperty("lovelaceRenderEvidence").orNull?.let {
    systemProperty("lovelaceRenderEvidence", file(it).absolutePath)
    outputs.upToDateWhen { false }
  }
  // The demo dashboards whose `mdi:` icons HaIconMapTest checks are all bundled.
  val demoDashboards =
    layout.projectDirectory.dir("../terrazzo-core/src/androidMain/resources/dashboards")
  inputs
    .dir(demoDashboards)
    .withPropertyName("demoDashboards")
    .withPathSensitivity(PathSensitivity.RELATIVE)
  systemProperty("harc.demoDashboards", demoDashboards.asFile.absolutePath)
  // `-PcardDesignsOut=<dir>` writes the spike's design-server files (manifest.json + blobs/)
  // there — see CardDesignTemplatesEndToEndTest.publishSpikeDesigns.
  providers.gradleProperty("cardDesignsOut").orNull?.let {
    systemProperty("cardDesignsOut", file(it).absolutePath)
    outputs.upToDateWhen { false }
  }
}

dependencies {
  api(platform(libs.rc.players.bom))
  api(project(":rc-components"))
  implementation(project(":ha-model"))
  implementation(project(":ha-client"))

  implementation(libs.kotlinx.coroutines.core)

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

  // RemoteCompose authoring + playback
  implementation(libs.remote.creation.compose)
  implementation(libs.remote.creation)
  implementation(libs.remote.creation.core)
  implementation(libs.remote.core)
  implementation(libs.remote.player.core)
  implementation(libs.remote.player.compose)
  implementation(libs.remote.player.view)
  implementation(libs.remote.material3)
  // Compose Multiplatform player — the default for every card host (see `CardPlayback`).
  api(libs.rc.player.compose)
  // Bakes launcher-widget designs: writes a card's values into a values-free template.
  implementation(libs.rc.player.edit)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlin.test.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.indriya)
  testImplementation(libs.ktor.client.mock)
  testImplementation("org.robolectric:robolectric:4.17")
  testImplementation(
    "com.google.android.apps.common.testing.accessibility.framework:accessibility-test-framework:4.1.1"
  )
  testImplementation(libs.compose.ui.test.junit4)
  debugImplementation(libs.compose.ui.test.manifest)
  robolectricSdk(libs.robolectric.sdk35)
}
