import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
  id("harc.base-conventions")
  id("harc.publishing")
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.android.kmp.library)
}

kotlin {
  jvmToolchain(libs.versions.java.get().toInt())

  val terrazzoKit = XCFramework("TerrazzoKit")

  android {
    namespace = "ee.schimke.ha.client"
    compileSdk {
      version = release(libs.versions.android.compileSdk.get().toInt()) { minorApiLevel = 2 }
    }
    minSdk = libs.versions.android.minSdk.get().toInt()
    withHostTest {}
  }
  jvm()
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser() }
  listOf(iosArm64(), iosSimulatorArm64()).forEach { appleTarget ->
    appleTarget.binaries.framework {
      baseName = "TerrazzoKit"
      export(project(":ha-model"))
      terrazzoKit.add(this)
    }
  }

  sourceSets {
    // JVM-only code Android and desktop share: the file-backed OfflineCacheStorage.
    androidMain { kotlin.srcDir("src/jdkMain/kotlin") }
    jvmMain { kotlin.srcDir("src/jdkMain/kotlin") }
    commonMain.dependencies {
      api(project(":ha-model"))
      // Public API: clients take an `HttpClientEngine` / `HttpClient` / `HaHttpClientFactory`
      // and return `Flow` / `StateFlow`, so both are `api`.
      api(libs.ktor.client.core)
      implementation(libs.ktor.client.websockets)
      implementation(libs.ktor.client.content.negotiation)
      implementation(libs.ktor.serialization.kotlinx.json)
      api(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.serialization.json)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
      implementation(libs.ktor.client.mock)
    }
    androidMain.dependencies {
      implementation(libs.ktor.client.okhttp)
      // `ForbiddenCertificatesHostnameVerifier` uses OkHttp's SPKI hashing.
      implementation(libs.okhttp)
    }
    jvmMain.dependencies {
      implementation(libs.ktor.client.okhttp)
      implementation(libs.okhttp)
    }
    jvmTest.dependencies {
      implementation(libs.ktor.server.test.host)
      implementation(libs.ktor.server.websockets)
    }
    wasmJsMain.dependencies { implementation(libs.ktor.client.js) }
    iosMain.dependencies { implementation(libs.ktor.client.darwin) }
  }
}

mavenPublishing {
  pom {
    name.set("Home Assistant client")
    description.set(
      "Ktor WebSocket and REST client for Home Assistant, plus Terrazzo integration, design-library and card-source clients: Kotlin Multiplatform",
    )
  }
}
