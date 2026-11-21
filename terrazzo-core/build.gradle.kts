plugins {
  id("harc.base-conventions")
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kmp.library)
  alias(libs.plugins.metro)
  alias(libs.plugins.tapmoc)
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  jvmToolchain(libs.versions.java.get().toInt())

  android {
    namespace = "ee.schimke.terrazzo.core"
    compileSdk {
      version = release(libs.versions.android.compileSdk.get().toInt()) { minorApiLevel = 2 }
    }
    // The project floor (29), not :app's 35: Wear (30), TV (29) and Quest (Horizon OS 34) reuse
    // the session, cache and token code, and copied it while this was 35. The API-35 RemoteCompose
    // widget lives in :app; Android lint's NewApi check guards any newer call made here.
    minSdk = libs.versions.android.minSdk.get().toInt()
    withHostTest {}
  }
  jvm()
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser() }

  sourceSets {
    commonMain.dependencies {
      // Public API: HaSession and OfflineCacheStore take and return ha-model and ha-client types.
      api(project(":ha-model"))
      api(project(":ha-client"))
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.kotlinx.serialization.protobuf)
      implementation(libs.ktor.client.core)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
    }
    jvmTest.dependencies {
      implementation(libs.ktor.server.test.host)
      implementation(libs.ktor.server.websockets)
    }
    androidMain.dependencies {
      implementation(libs.androidx.datastore)
      implementation(libs.androidx.datastore.preferences)
      implementation(libs.appauth)
      implementation(libs.androidx.credentials)
      implementation(libs.androidx.credentials.play.services.auth)
      implementation(libs.ktor.client.okhttp)
    }
  }
}

tapmoc {
  java(libs.versions.java.get().toInt())
  kotlin(libs.versions.kotlin.get())
}
