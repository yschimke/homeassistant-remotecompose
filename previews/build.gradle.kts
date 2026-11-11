plugins {
  id("harc.base-conventions")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "ee.schimke.ha.previews"
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
}

// The fonts card designs name (card-fonts/), as classpath resources: the app fetches them from the
// design server, the previews draw with the same files (HaFontLibrary seeds itself from them).
abstract class CardFontResources : DefaultTask() {
  @get:InputDirectory abstract val fonts: DirectoryProperty

  @get:OutputDirectory abstract val output: DirectoryProperty

  @TaskAction
  fun copy() {
    val into = output.get().asFile.resolve("card-fonts")
    into.deleteRecursively()
    into.mkdirs()
    fonts
      .get()
      .asFile
      .listFiles { file -> file.name == "fonts.json" || file.extension in setOf("ttf", "otf") }
      ?.forEach { it.copyTo(into.resolve(it.name)) }
  }
}

val cardFonts by
  tasks.registering(CardFontResources::class) {
    fonts.set(rootProject.layout.projectDirectory.dir("card-fonts"))
  }

androidComponents.onVariants { variant ->
  variant.sources.resources?.addGeneratedSourceDirectory(cardFonts, CardFontResources::output)
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
  // Icons for the Tier-2 card previews' hand-built plain data.
  implementation(libs.compose.material.icons.extended)
  implementation(libs.compose.ui.tooling.preview)
  debugImplementation(libs.compose.ui.tooling)

  implementation(libs.remote.player.compose)
  implementation(libs.remote.player.view)
  implementation(libs.remote.tooling.preview)

  // Render-harness seam for the `.rc` IR sidecar — see `RcDocumentCapture.kt`.
  // Only the preview module needs it; nothing shipped in the app depends on it.
  implementation(libs.composeai.data.render.core)
  implementation(platform(libs.composeai.daemon.bom))
  implementation(libs.composeai.preview.annotations)
  implementation("ee.schimke.composeai:data-preview-overrides-runtime")

  // Coil + fake engine — previews that exercise `CoilBitmapLoader`
  // resolve named bitmaps through `FakeImageLoaderEngine` instead of
  // hitting the network, so renders are offline / deterministic.
  implementation(libs.coil)
  implementation(libs.coil.test)

  implementation(libs.kotlinx.serialization.json)
}

// The full catalog includes large tablet dashboards. Its Robolectric render task is a Test
// process; Gradle's default 512 MiB heap fails in InMemoryStillCapture while copying that bitmap.
tasks.withType<Test>().configureEach { maxHeapSize = "2g" }
