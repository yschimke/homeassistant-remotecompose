import com.github.triplet.gradle.androidpublisher.ReleaseStatus

val appVersionName = "0.1.60" // x-release-please-version

// Pack MAJOR.MINOR.PATCH into a monotonic int, times ten for a form-factor
// lane: phone ends in 0, `:wear` (same package) in 2. Both share the base so
// each release's phone code exceeds the previous release's Wear code — Play
// Publisher refuses an upload below the app's highest existing code. Caps at
// major < 21.
val appVersionCode: Int = run {
  val parts = appVersionName.split(".", "-").mapNotNull { it.toIntOrNull() }
  val major = parts.getOrNull(0) ?: 0
  val minor = parts.getOrNull(1) ?: 0
  val patch = parts.getOrNull(2) ?: 0
  (major * 10_000 + minor * 100 + patch) * 10
}
  .coerceAtLeast(1)

plugins {
  id("harc.base-conventions")
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.metro)
  alias(libs.plugins.ksp)
  alias(libs.plugins.tapmoc)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.play.publisher)
  alias(libs.plugins.androidx.baselineprofile)
}

// Off-device crash reporting goes through the CrashReporter seam
// (src/main/.../crash/CrashReporter.kt); which backend gets compiled in is a
// build-time choice, `-Pharc.crashReporter=<auto|firebase|sentry|none>`,
// resolved by selectCrashReporter() in build-logic (shared with `:wear`).
// With `none` the in-app LogStore stays the only sink. A backend's plugins,
// SDK, source set and keep rules join the build only when it's selected, so
// a `none` build carries no crash SDK at all.
val crashReporter: String = selectCrashReporter()
val crashlyticsEnabled = crashReporter == "firebase"

if (crashlyticsEnabled) {
  apply(plugin = "com.google.gms.google-services")
  apply(plugin = "com.google.firebase.crashlytics")
}

android {
  namespace = "ee.schimke.terrazzo"
  compileSdk {
    version = release(libs.versions.android.compileSdk.get().toInt()) { minorApiLevel = 1 }
  }
  defaultConfig {
    applicationId = "ee.schimke.harc"
    // Widget playback via RemoteViews.DrawInstructions needs API 35+
    // (VANILLA_ICE_CREAM). We still compile / target the newest
    // available SDK via `compileSdk` / `targetSdk`. Keeping minSdk
    // at 35 (not 36) lets the Robolectric framework the compose-preview
    // CLI ships with parse this module's apk-for-local-test during
    // composePreviewRender.
    minSdk = 35
    targetSdk = libs.versions.android.targetSdk.get().toInt()
    versionCode = appVersionCode
    versionName = appVersionName

    // Exposed to AndroidManifest via placeholders so the IndieAuth
    // redirect scheme is declared in one place.
    manifestPlaceholders["appAuthRedirectScheme"] = "rcha"

    // Read at runtime by CrashReporter.create() to pick the backend
    // compiled in above ("firebase" or "none").
    buildConfigField("String", "CRASH_REPORTER", "\"$crashReporter\"")

    // Gates MainActivity's demo-mode launch extra. On for debug builds and the
    // `benchmarkRelease` / `nonMinifiedRelease` variants `:app-benchmark` drives (see
    // `androidComponents` below), off for anything shipped.
    buildConfigField("boolean", "TEST_HOOKS", "false")

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
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
      isMinifyEnabled = true
      isShrinkResources = true
      if (releaseKeystorePath != null) {
        signingConfig = signingConfigs.getByName("release")
      }
      proguardFiles(file("proguard-rules.pro"))
      // FirebaseCrashReporter is loaded reflectively, so R8 would strip or
      // rename it in minified builds without an explicit keep rule. Only
      // needed (and the class only present) when firebase is selected; the
      // experimental build type inherits this via initWith below.
      if (crashlyticsEnabled) {
        proguardFiles(file("crashlytics-rules.pro"))
      }
    }
    create("experimental") {
      initWith(getByName("release"))
      matchingFallbacks.add("release")
      applicationIdSuffix = ".experimental"
    }
  }
  // `experimental` is a local side-by-side install (`.experimental`
  // applicationId suffix); it is never shipped to Play — the internal track
  // hosts ee.schimke.harc. Disable Play publishing for it so the umbrella
  // `:app:publishBundle` task doesn't drag in `bundleExperimental`. That
  // assembly would otherwise fail at `processExperimentalGoogleServices`
  // (Crashlytics' google-services.json has no client for the suffixed
  // package) and, even past that, Play would reject a bundle whose package
  // isn't the listing's. The release job runs `publishReleaseBundle` anyway,
  // which also skips the baseline profile plugin's `benchmark*` and
  // `nonMinified*` variants.
  playConfigs { register("experimental") { enabled.set(false) } }
  testOptions { unitTests.isIncludeAndroidResources = true }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  // The Firebase-backed CrashReporter only joins the compilation when it's
  // the selected backend; src/main stays free of any Firebase import so the
  // no-key build needs none of the SDK.
  if (crashlyticsEnabled) {
    sourceSets.getByName("main").kotlin.directories.add("src/crashlytics/kotlin")
  }
  kotlin { jvmToolchain(libs.versions.java.get().toInt()) }
}

// The baseline profile plugin derives `nonMinifiedRelease` (profile generation) and
// `benchmarkRelease` (macrobenchmarks) from `release`. Both are non-debuggable, so the
// demo-mode hatch needs its own switch; `:app-benchmark` launches straight into demo data.
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
  // Profiles are generated on an emulator (`.github/workflows/baseline-profiles.yml`)
  // and checked in under src/main/generated/baselineProfiles; a normal build only
  // merges the checked-in file and never needs a device.
  automaticGenerationDuringBuild = false
  saveInSrc = true
  // `experimental` is a local side-by-side install; it ships the release profile via
  // `mergeIntoMain`, without its own generation variants.
  mergeIntoMain = true
}

// Robolectric's SDK jar, resolved by Gradle (offline at test time) — same setup as
// `:rc-converter`. Used by the demo-image tests, which draw real bitmaps.
val robolectricSdk by configurations.creating {
  isCanBeConsumed = false
  isTransitive = false
}
val prepareRobolectricSdk by
  tasks.registering(Sync::class) {
    from(robolectricSdk)
    into(layout.buildDirectory.dir("robolectric-sdk"))
  }

// This module's own unit-test tasks only: `withType<Test>()` alone also matches the compose-preview
// plugin's `composePreviewRender` Test task, whose renderer resolves its own (newer) SDK and must
// not be forced offline into a directory holding only this jar.
tasks
  .withType<Test>()
  .matching { it.name.startsWith("test") && it.name.endsWith("UnitTest") }
  .configureEach {
    inputs.files(robolectricSdk)
    dependsOn(prepareRobolectricSdk)
    systemProperty("robolectric.offline", "true")
    systemProperty(
      "robolectric.dependency.dir",
      layout.buildDirectory.dir("robolectric-sdk").get().asFile.absolutePath,
    )
    // `-PdemoImagesOut=<dir>` writes the demo stand-in pictures there as PNGs, for a look.
    providers.gradleProperty("demoImagesOut").orNull?.let {
      systemProperty("demoImagesOut", file(it).absolutePath)
      outputs.upToDateWhen { false }
    }
  }

tapmoc {
  java(libs.versions.java.get().toInt())
  kotlin(libs.versions.kotlin.get())
}

play {
  track.set("internal")
  defaultToAppBundles.set(true)
  // Roll straight out to internal testers; promote beyond internal in the Console.
  releaseStatus.set(ReleaseStatus.COMPLETED)
  // Skip API calls in CI runs that build but don't publish (e.g. PRs).
  enabled.set(System.getenv("ANDROID_PUBLISHER_CREDENTIALS") != null)
}

dependencies {
  implementation(project(":app-ui"))
  implementation(project(":ha-model"))
  implementation(project(":ha-client"))
  implementation(project(":rc-converter"))
  implementation(project(":rc-components"))
  implementation(project(":rc-card-shutter"))
  implementation(project(":rc-image-coil"))
  implementation(project(":terrazzo-core"))

  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  implementation(libs.compose.foundation)
  implementation(libs.compose.material3)
  implementation(libs.compose.material.icons.extended)
  implementation(libs.compose.ui.text.google.fonts)
  implementation(libs.compose.ui.tooling.preview)
  debugImplementation(libs.compose.ui.tooling)
  // Debug-only: @AnimatedPreview drives the compose-preview GIF capture
  // of the card-flash debug feature. Kept out of the release APK.
  debugImplementation(platform(libs.composeai.daemon.bom))
  debugImplementation(libs.composeai.preview.annotations)
  implementation(libs.activity.compose)
  implementation(libs.materialkolor)
  implementation(libs.androidx.profileinstaller)
  baselineProfile(project(":app-benchmark"))

  implementation(libs.okhttp)
  implementation(libs.okhttp.coroutines)

  implementation(libs.remote.creation.compose)
  implementation(libs.remote.creation.core)
  implementation(libs.remote.player.compose)
  implementation(libs.remote.tooling.preview)

  implementation(libs.androidx.browser)
  // Proto DataStore for the wear sync layer. Schema is documented
  // in `src/main/proto/wear_sync.proto`; we encode @Serializable
  // Kotlin data classes to the same proto wire format via
  // kotlinx-serialization-protobuf. (TODO: switch to Wire once its
  // Gradle plugin recognises AGP 9's built-in Kotlin.)
  implementation(libs.androidx.datastore)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.kotlinx.serialization.protobuf)
  implementation(libs.play.services.wearable)
  implementation(libs.horologist.datalayer)
  implementation(libs.horologist.datalayer.phone)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.lifecycle.viewmodel)
  implementation(libs.androidx.work.runtime)
  // AppFunctions (`assistant/`): the service a permitted on-device agent calls. The compiler
  // generates the concrete service and its function XML asset.
  implementation(libs.androidx.appfunctions)
  ksp(libs.androidx.appfunctions.compiler)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.material3.adaptive.navigation.suite)
  implementation(libs.appauth)
  implementation(libs.coil)
  implementation(libs.coil.network.okhttp)

  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)

  // Firebase SDK — only when firebase is the selected crash reporter (see
  // crashReporter). Analytics rides alongside Crashlytics: it auto-inits
  // via the google-services-generated config and gives Crashlytics richer
  // breadcrumb / user-velocity data; no app code needed.
  if (crashlyticsEnabled) {
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.analytics)
  }

  testImplementation(libs.kotlin.test.junit)
  testImplementation(libs.compose.ui.test.junit4)
  debugImplementation(libs.compose.ui.test.manifest)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation("org.robolectric:robolectric:4.17")
  robolectricSdk(libs.robolectric.sdk35)
  // Offline-first integration tests spin up real fake servers so the
  // round-trip through HaClient (WebSocket) and AddonClient (HTTP) is
  // exercised against an actual socket — not a Ktor MockEngine.
  //
  //   - mockserver-netty for HTTP REST endpoints (AddonClient probe +
  //     dashboard fetch). Picked over OkHttp's MockWebServer because
  //     mockserver's expectations API is closer to integration-test
  //     ergonomics for offline-first scenarios.
  //   - ktor-server-cio + ktor-server-websockets for the HA-protocol
  //     WebSocket fake. mockserver's WebSocket support is for its own
  //     callback infrastructure, not for serving an arbitrary text-
  //     based protocol like HA's auth_required / commands-by-id; Ktor
  //     server is already a project dep (used by the addon-server
  //     module) and is a natural fit.
  testImplementation(libs.mockserver.netty)
  testImplementation(libs.mockserver.client.java)
  testImplementation(libs.ktor.server.cio)
  testImplementation(libs.ktor.server.websockets)

  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(platform(libs.compose.bom))
  androidTestImplementation(libs.compose.ui.test.junit4)
  androidTestImplementation(libs.remote.creation)
  // uiautomator drives the long-press install flow via the actual
  // Android input pipeline — Compose UI tests can't reliably exercise
  // the Initial-pass pointer-input path that pre-empts the RC player.
  androidTestImplementation(libs.androidx.test.uiautomator)
  // ui-test-manifest bundles a ComponentActivity into the debug APK so
  // `createComposeRule()` can host @Composable content without an
  // Activity of our own.
  debugImplementation(libs.compose.ui.test.manifest)
}
