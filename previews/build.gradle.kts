plugins {
  id("harc.base-conventions")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "ee.schimke.ha.previews"
  compileSdk = libs.versions.android.compileSdk.get().toInt()
  defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
  buildFeatures { compose = true }
  compileOptions {
    sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
  }
  kotlin { jvmToolchain(libs.versions.java.get().toInt()) }
}

// Route `-PhaRcPlayer=<java|cmp-android>` into the compose-preview render fork, which is how the
// view lane gets re-rendered for a visual diff against the default (see `RcPreviewHost.kt`).
//
// It has to be set on the task. The render runs as a Gradle `Test` task in a forked JVM whose
// system properties the compose-preview plugin curates — a `-D` on the command line never arrives
// — and whose environment is inherited from the **Gradle daemon**, not from the shell that typed
// the command. So `HA_RC_PLAYER=java compose-preview render` silently does nothing against a
// daemon that was already warm, which reads exactly like the flag being ignored. This path works
// from a cold or warm daemon alike.
tasks.withType<Test>().configureEach {
  providers.gradleProperty("haRcPlayer").orNull?.let { systemProperty("ha.rc.player", it) }
}

dependencies {
  implementation(project(":ha-model"))
  implementation(project(":rc-converter"))
  implementation(project(":rc-card-shutter"))
  implementation(project(":rc-components-ui"))
  implementation(project(":rc-image-coil"))

  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  implementation(libs.compose.foundation)
  implementation(libs.compose.material3)
  implementation(libs.compose.ui.tooling.preview)
  debugImplementation(libs.compose.ui.tooling)

  implementation(libs.remote.creation.compose)
  implementation(libs.remote.creation)
  implementation(libs.remote.creation.core)
  implementation(libs.remote.player.compose)
  implementation(libs.remote.player.view)
  implementation(libs.remote.tooling.preview)

  // Render-harness seam for the `.rc` IR sidecar — see `RcDocumentCapture.kt`.
  // Only the preview module needs it; nothing shipped in the app depends on it.
  implementation(libs.composeai.data.render.core)

  // The Compose-native Remote Compose player previews draw with by default —
  // see `RcPreviewHost.kt`. Preview-only, like the line above: the app keeps
  // playing documents through `remote-player-view` via `rc-components-ui`.
  implementation(libs.composeai.rc.embedded.player)

  // Coil + fake engine — previews that exercise `CoilBitmapLoader`
  // resolve named bitmaps through `FakeImageLoaderEngine` instead of
  // hitting the network, so renders are offline / deterministic.
  implementation(libs.coil)
  implementation(libs.coil.test)

  implementation(libs.kotlinx.serialization.json)
}
