plugins {
  id("harc.base-conventions")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
}

// Generates the card design library the design server hosts: every template shape the bundled demo
// dashboards produce, for each theme, with preview renders and a browsable catalog. It is a
// Robolectric test (captures need Android), so `generateCardDesigns` runs the unit tests and the
// library lands in `build/card-designs/` (override with `-PcardDesignsOut=<dir>`). See
// docs/architecture/card-design-server.md.
android {
  namespace = "ee.schimke.ha.designs"
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

// Robolectric's SDK jar, resolved by Gradle (offline at test time) — same setup as `:rc-converter`.
val robolectricSdk by configurations.creating {
  isCanBeConsumed = false
  isTransitive = false
}
val prepareRobolectricSdk by
  tasks.registering(Sync::class) {
    from(robolectricSdk)
    into(layout.buildDirectory.dir("robolectric-sdk"))
  }

val cardDesignsOut: File =
  providers.gradleProperty("cardDesignsOut").orNull?.let { file(it) }
    ?: layout.buildDirectory.dir("card-designs").get().asFile

tasks
  .withType<Test>()
  .matching { it.name.endsWith("UnitTest") }
  .configureEach {
    inputs.files(robolectricSdk)
    // The published sticker-sheet selection is part of the preview discovery contract.
    inputs.file(rootProject.file("catalog.spec.json"))
    inputs.dir(layout.projectDirectory.dir("ui-builder/designs"))
    dependsOn(prepareRobolectricSdk)
    systemProperty("robolectric.offline", "true")
    systemProperty(
      "robolectric.dependency.dir",
      layout.buildDirectory.dir("robolectric-sdk").get().asFile.absolutePath,
    )
    systemProperty("cardDesignsOut", cardDesignsOut.absolutePath)
    outputs.dir(cardDesignsOut)
    maxHeapSize = "2g"
  }

tasks.register("generateCardDesigns") {
  group = "card designs"
  description = "Generates the card design library into $cardDesignsOut."
  dependsOn("testDebugUnitTest")
}

dependencies {
  testImplementation(project(":ha-model"))
  testImplementation(project(":ha-client"))
  testImplementation(project(":rc-converter"))
  testImplementation(project(":rc-player-ui"))
  testImplementation(project(":rc-components"))
  // Card designs authored in UI Builder (`ui-builder/designs/*.uid`), drawn by the catalog's own
  // components: CardDesignDocument.
  testImplementation(project(":ha-cards-catalog"))
  // The bundled demo dashboards: the library's source cards.
  testImplementation(project(":previews"))

  testImplementation(platform(libs.compose.bom))
  testImplementation(libs.compose.ui)
  testImplementation(libs.compose.foundation)
  testImplementation(libs.remote.creation.core)
  testImplementation(libs.remote.creation)
  testImplementation(libs.compose.ui.test.junit4)
  debugImplementation(platform(libs.compose.bom))
  debugImplementation(libs.compose.ui.test.manifest)
  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlin.test.junit)
  testImplementation(libs.kotlinx.serialization.json)
  testImplementation("org.robolectric:robolectric:4.17")
  robolectricSdk(libs.robolectric.sdk35)
}
