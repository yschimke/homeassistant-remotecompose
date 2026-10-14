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
  robolectricSdk(libs.robolectric.sdk35)
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
  // The catalog's closed icon set resolves keys to the Material glyphs the cards draw.
  implementation(libs.compose.material.icons.extended)
  implementation(libs.kotlinx.serialization.json)
  testImplementation("ee.schimke.composeai:preview-discovery:2.58.0")
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

// Android implementations remain Android bytecode: an Android/Remote Compose host supplies the
// shared runtime. Package the actual app libraries, not recompilations or host-side facsimiles.
val stageAndroidCatalogBundle =
  tasks.register<Sync>("stageAndroidCatalogBundle") {
    dependsOn("exportTypedCatalog")
    into(layout.buildDirectory.dir("android-catalog-bundle"))
    from(layout.buildDirectory.dir("catalog/testDebugUnitTest"))
    from("fixtures") { into("designs") }
    for (module in
      listOf("ui-builder-catalog", "rc-components-ui", "rc-components", "rc-converter")) {
      val library = project(":$module")
      dependsOn(":$module:bundleDebugAar")
      from(
        library.layout.buildDirectory.file(
          "intermediates/aar_main_jar/debug/syncDebugLibJars/classes.jar"
        )
      ) {
        into("lib")
        rename { "$module.jar" }
      }
      from(library.layout.buildDirectory.file("outputs/aar/$module-debug.aar")) { into("android") }
    }
  }
val packageAndroidCatalogBundle =
  tasks.register<Zip>("packageAndroidCatalogBundle") {
    group = "ui builder"
    dependsOn(stageAndroidCatalogBundle)
    from(layout.buildDirectory.dir("android-catalog-bundle"))
    archiveFileName.set("homeassistant-remotecompose-android-catalog.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
  }

tasks.register<Sync>("unpackAndroidCatalogBundle") {
  dependsOn(packageAndroidCatalogBundle)
  from(zipTree(packageAndroidCatalogBundle.flatMap { it.archiveFile }))
  into(layout.buildDirectory.dir("installed-android-catalog"))
}
