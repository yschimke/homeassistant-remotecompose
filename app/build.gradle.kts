import com.github.triplet.gradle.androidpublisher.ReleaseStatus

val appVersionName = "0.1.3" // x-release-please-version

// Pack MAJOR.MINOR.PATCH into a monotonic int. Caps at major < 22.
val appVersionCode: Int =
  run {
      val parts = appVersionName.split(".", "-").mapNotNull { it.toIntOrNull() }
      val major = parts.getOrNull(0) ?: 0
      val minor = parts.getOrNull(1) ?: 0
      val patch = parts.getOrNull(2) ?: 0
      major * 10_000 + minor * 100 + patch
    }
    .coerceAtLeast(1)

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.compose.preview)
  alias(libs.plugins.metro)
  alias(libs.plugins.tapmoc)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.play.publisher)
}

android {
  namespace = "ee.schimke.terrazzo"
  compileSdk = libs.versions.android.compileSdk.get().toInt()
  defaultConfig {
    applicationId = "ee.schimke.harc"
    // Widget playback via RemoteViews.DrawInstructions needs API 35+
    // (VANILLA_ICE_CREAM). We still compile / target the newest
    // available SDK via `compileSdk` / `targetSdk`. Keeping minSdk
    // at 35 (not 36) lets Robolectric's SDK-35 framework — which
    // compose-preview 0.7.8 tops out at — parse this module's
    // apk-for-local-test during renderPreviews.
    minSdk = 35
    targetSdk = libs.versions.android.targetSdk.get().toInt()
    versionCode = appVersionCode
    versionName = appVersionName

    // Exposed to AndroidManifest via placeholders so the IndieAuth
    // redirect scheme is declared in one place.
    manifestPlaceholders["appAuthRedirectScheme"] = "rcha"

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
    getByName("release") {
      isMinifyEnabled = true
      isShrinkResources = true
      if (releaseKeystorePath != null) {
        signingConfig = signingConfigs.getByName("release")
      }
    }
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  kotlin { jvmToolchain(libs.versions.java.get().toInt()) }
}

tapmoc {
  java(libs.versions.java.get().toInt())
  kotlin(libs.versions.kotlin.get())
}

composePreview {
  variant.set("debug")
  sdkVersion.set(35)
  enabled.set(true)
}

play {
  track.set("internal")
  defaultToAppBundles.set(true)
  releaseStatus.set(ReleaseStatus.DRAFT)
  // Skip API calls in CI runs that build but don't publish (e.g. PRs).
  enabled.set(System.getenv("ANDROID_PUBLISHER_CREDENTIALS") != null)
}

dependencies {
  implementation(project(":ha-model"))
  implementation(project(":ha-client"))
  implementation(project(":rc-converter"))
  implementation(project(":rc-components"))
  implementation(project(":terrazzo-core"))

  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  implementation(libs.compose.foundation)
  implementation(libs.compose.material3)
  implementation(libs.compose.material.icons.extended)
  implementation(libs.compose.ui.text.google.fonts)
  implementation(libs.compose.ui.tooling.preview)
  debugImplementation(libs.compose.ui.tooling)
  implementation(libs.activity.compose)
  implementation(libs.materialkolor)

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
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.work.runtime)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.material3.adaptive.navigation.suite)
  implementation(libs.appauth)

  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.kotlin.test.junit)
  testImplementation(libs.kotlinx.coroutines.test)
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
  // uiautomator drives the long-press install flow via the actual
  // Android input pipeline — Compose UI tests can't reliably exercise
  // the Initial-pass pointer-input path that pre-empts the RC player.
  androidTestImplementation(libs.androidx.test.uiautomator)
  // ui-test-manifest bundles a ComponentActivity into the debug APK so
  // `createComposeRule()` can host @Composable content without an
  // Activity of our own.
  debugImplementation(libs.compose.ui.test.manifest)
}
