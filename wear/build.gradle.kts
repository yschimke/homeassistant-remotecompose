val wearVersionName = "0.1.61" // x-release-please-version

// Keep Wear version tied to release-please while reserving a unique code
// lane for the shared package id (`ee.schimke.harc`): same base as `:app`
// (whose lane is 0), suffix 2.
val wearVersionCode: Int = run {
  val parts = wearVersionName.split(".", "-").mapNotNull { it.toIntOrNull() }
  val major = parts.getOrNull(0) ?: 0
  val minor = parts.getOrNull(1) ?: 0
  val patch = parts.getOrNull(2) ?: 0
  (major * 10_000 + minor * 100 + patch) * 10 + 2
}
  .coerceAtLeast(2)

plugins {
  id("harc.base-conventions")
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.play.publisher)
  alias(libs.plugins.androidx.baselineprofile)
}

// Crash reporting: same `-Pharc.crashReporter` switch as `:app` (see
// selectCrashReporter() in build-logic). With firebase selected, Crashlytics
// reports fatal crashes with no app code: Firebase initialises itself at
// process start from the config the Google Services plugin generates. Watch
// reports land in the phone's Crashlytics project, told apart by their
// version code (lane 2, below).
val crashlyticsEnabled = selectCrashReporter() == "firebase"

if (crashlyticsEnabled) {
  apply(plugin = "com.google.gms.google-services")
  // Also stamps the build id the Crashlytics SDK needs at startup.
  apply(plugin = "com.google.firebase.crashlytics")
  // One key for both apps: the plugin only looks for google-services.json in
  // this module, so point its tasks at `:app`'s copy instead of keeping a
  // second one. The plugin sets the files in its own onVariants callback;
  // registering this from a later onVariants puts it after the plugin's
  // configure action. Its classes are only on the classpath when a key is
  // present, hence the reflective property lookup.
  androidComponents.onVariants { variant ->
    val taskName = "process${variant.name.replaceFirstChar { it.uppercase() }}GoogleServices"
    tasks
      .matching { it.name == taskName }
      .configureEach {
        @Suppress("UNCHECKED_CAST")
        val jsonFiles =
          javaClass.getMethod("getGoogleServicesJsonFiles").invoke(this)
            as Property<Collection<File>>
        jsonFiles.set(listOf(rootDir.resolve(GOOGLE_SERVICES_JSON)))
      }
  }
}

android {
  namespace = "ee.schimke.terrazzo.wear"
  compileSdk {
    version = release(libs.versions.android.compileSdk.get().toInt()) { minorApiLevel = 1 }
  }
  defaultConfig {
    applicationId = "ee.schimke.harc"
    minSdk = 30
    targetSdk = libs.versions.android.targetSdk.get().toInt()
    versionCode = wearVersionCode
    versionName = wearVersionName
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    // Gates WearMainActivity's seeded-pins launch extra: on for debug and the variants
    // `:wear-benchmark` drives (see `androidComponents` below), off for anything shipped.
    buildConfigField("boolean", "TEST_HOOKS", "false")
  }
  // Same upload key as `:app` — Wear ships inside the phone's Play listing
  // (`ee.schimke.harc`), so both must be signed by one key lineage.
  val releaseKeystorePath = System.getenv("HARC_KEYSTORE_PATH")
  signingConfigs {
    if (releaseKeystorePath != null) {
      create("release") {
        storeFile = file(releaseKeystorePath)
        storePassword = System.getenv("HARC_KEYSTORE_PASSWORD")
        keyAlias = System.getenv("HARC_KEY_ALIAS")
        keyPassword = System.getenv("HARC_KEY_PASSWORD")
      }
    }
  }
  buildTypes {
    getByName("debug") { buildConfigField("boolean", "TEST_HOOKS", "true") }
    getByName("release") {
      // Unshrunk, the APK carries ~83 MB of dex: on a Pixel Watch a cold start spent hundreds of
      // milliseconds faulting it in (see docs/testing/wear-results.md).
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(file("proguard-rules.pro"))
      if (releaseKeystorePath != null) {
        signingConfig = signingConfigs.getByName("release")
      }
    }
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  compileOptions {
    sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
  }
  kotlin { jvmToolchain(libs.versions.java.get().toInt()) }
  testOptions { unitTests.isIncludeAndroidResources = true }
  lint {
    // Compose Preview plugin generates preview parameter providers under
    // build/generated that can reference @RestrictTo APIs from Glance Wear.
    // These generated stubs are not shipped runtime code, so skip generated
    // source lint checks for this module.
    checkGeneratedSources = false
  }
}

// Robolectric's SDK jar, resolved by Gradle (offline at test time) — same setup as
// `:rc-converter`. Used by the Wear widget design test, which captures real Glance Wear documents.
val robolectricSdk by configurations.creating {
  isCanBeConsumed = false
  isTransitive = false
}
val prepareRobolectricSdk by
  tasks.registering(Sync::class) {
    from(robolectricSdk)
    into(layout.buildDirectory.dir("robolectric-sdk"))
  }

// Unit tests only: the Compose Preview renderer (`composePreviewRender`) is a Test task too, and
// resolves its own Robolectric SDK.
tasks
  .withType<Test>()
  .matching { it.name.endsWith("UnitTest") }
  .configureEach {
    inputs.files(robolectricSdk)
    // The checked-in card designs WearCardDesignParityTest draws.
    inputs.dir(rootProject.layout.projectDirectory.dir("card-designs/ui-builder/designs"))
    systemProperty(
      "cardDesignsDir",
      rootProject.layout.projectDirectory
        .dir("card-designs/ui-builder/designs")
        .asFile
        .absolutePath,
    )
    dependsOn(prepareRobolectricSdk)
    systemProperty("robolectric.offline", "true")
    systemProperty(
      "robolectric.dependency.dir",
      layout.buildDirectory.dir("robolectric-sdk").get().asFile.absolutePath,
    )
  }

// The baseline profile plugin derives `nonMinifiedRelease` and `benchmarkRelease` from `release`;
// both are non-debuggable, so the seeded-pins hatch needs its own switch.
androidComponents {
  onVariants(selector().withBuildType("nonMinifiedRelease")) { it.enableTestHooks() }
  onVariants(selector().withBuildType("benchmarkRelease")) { it.enableTestHooks() }
}

fun com.android.build.api.variant.ApplicationVariant.enableTestHooks() {
  buildConfigFields?.put(
    "TEST_HOOKS",
    com.android.build.api.variant.BuildConfigField("boolean", "true", "baseline profile variant"),
  )
}

baselineProfile {
  // Generated on a Wear emulator (`.github/workflows/baseline-profiles.yml`) and checked in
  // under src/release/generated/baselineProfiles; a normal build never needs a device.
  automaticGenerationDuringBuild = false
  saveInSrc = true
}

play {
  // Wear's dedicated form-factor track. Publishing to the shared `internal`
  // track instead would replace the phone bundle's release on it.
  track.set("wear:internal")
  defaultToAppBundles.set(true)
  // Roll straight out to internal testers; promote to wear:production in the Console.
  releaseStatus.set(com.github.triplet.gradle.androidpublisher.ReleaseStatus.COMPLETED)
  // Only publish when CI/local env provides a Play service-account json file path.
  enabled.set(System.getenv("ANDROID_PUBLISHER_CREDENTIALS") != null)
}

dependencies {
  implementation(platform(libs.rc.players.bom))
  implementation(project(":app-ui"))
  implementation(project(":rc-components"))
  implementation(project(":rc-converter"))
  implementation(project(":rc-player-ui"))
  implementation(project(":rc-card-shutter"))
  implementation(project(":ha-model"))
  // Reads the cards' values from Home Assistant when the phone has none to send.
  implementation(project(":ha-client"))

  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  implementation(libs.compose.foundation)
  // `rc-components` exposes terrazzoColorScheme returning an androidx.compose.material3
  // ColorScheme;
  // we include material3 here only to unpack that for the Wear `ColorScheme.copy(...)` mapping.
  implementation(libs.compose.material3)
  implementation(libs.compose.ui.text.google.fonts)
  implementation(libs.compose.ui.tooling.preview)
  debugImplementation(libs.compose.ui.tooling)
  implementation(libs.activity.compose)
  implementation(libs.androidx.profileinstaller)
  baselineProfile(project(":wear-benchmark"))

  implementation(libs.wear.compose.material3)
  implementation(libs.wear.compose.foundation)
  implementation(libs.wear.tooling.preview)

  // AndroidX Glance Wear — runtime widget rendering. Pulls
  // remote-creation-compose, lifecycle-service, etc. transitively.
  implementation(libs.glance.wear)
  implementation(libs.glance.wear.core)

  // Vendored AOSP preview utility for Glance Wear widgets needs
  // RemoteDocPreview from this artifact. Tracking removal via the
  // TODOs on WearWidgetPreview.kt / WearWidgetParamsProvider.kt — once
  // androidx.glance.wear:wear-tooling-preview ships a non-empty
  // implementation, drop the vendored copies (and re-evaluate keeping
  // this dep — it only ships RemoteDocPreview today).
  implementation(libs.remote.tooling.preview)
  // The AndroidX Compose player. WearWidgetPreviewSnapshot now plays through
  // the Compose Multiplatform `RcComposePlayer` (via rc-converter's
  // HaRcComposePlayer); this stays for EmbeddedSystemThemeTest, which
  // exercises the AndroidX embedded player's theming directly.
  implementation(libs.remote.player.compose)
  implementation(libs.remote.player.core)
  implementation(libs.remote.material3)

  // Proto DataStore + Horologist data layer for sync with phone.
  // Schema is documented in `src/main/proto/wear_sync.proto`; we
  // encode @Serializable Kotlin data classes to the same proto wire
  // format via kotlinx-serialization-protobuf. (TODO: switch to Wire
  // once its Gradle plugin recognises AGP 9's built-in Kotlin.)
  implementation(libs.androidx.datastore)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.kotlinx.serialization.protobuf)
  implementation(libs.play.services.wearable)
  implementation(libs.horologist.datalayer)
  implementation(libs.horologist.datalayer.watch)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.process)

  // Firebase Crashlytics — only when firebase is the selected crash reporter
  // (see crashlyticsEnabled). No Analytics on the watch.
  if (crashlyticsEnabled) {
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
  }

  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  // Wear widget templates are made playable on the watch: container values, density, profile.
  implementation(libs.rc.player.edit)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlin.test.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation("org.robolectric:robolectric:4.17")
  // Card designs authored in UI Builder, drawn by the catalog's components: the Wear surface is
  // checked against them (WearCardDesignParityTest).
  testImplementation(project(":ha-cards-catalog"))
  testImplementation(libs.compose.ui.test.junit4)
  robolectricSdk(libs.robolectric.sdk35)

  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  // Compose's transitive Espresso calls InputManager.getInstance, removed in API 37.
  androidTestImplementation(libs.androidx.test.espresso.core)
  androidTestImplementation(platform(libs.compose.bom))
  androidTestImplementation(libs.compose.ui.test.junit4)
  androidTestImplementation(libs.remote.core)
  androidTestImplementation(libs.remote.creation.core)
  androidTestImplementation(libs.remote.creation)
  androidTestImplementation(libs.remote.player.compose)
  debugImplementation(libs.compose.ui.test.manifest)
}
