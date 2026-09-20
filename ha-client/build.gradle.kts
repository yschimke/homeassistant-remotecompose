import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
  id("harc.base-conventions")
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.android.kmp.library)
}

kotlin {
  jvmToolchain(libs.versions.java.get().toInt())

  val terrazzoKit = XCFramework("TerrazzoKit")

  android {
    namespace = "ee.schimke.ha.client"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    minSdk = libs.versions.android.minSdk.get().toInt()
    withHostTest {}
  }
  jvm()
  listOf(iosArm64(), iosSimulatorArm64()).forEach { appleTarget ->
    appleTarget.binaries.framework {
      baseName = "TerrazzoKit"
      export(project(":ha-model"))
      terrazzoKit.add(this)
    }
  }

  sourceSets {
    commonMain.dependencies {
      api(project(":ha-model"))
      implementation(libs.ktor.client.core)
      implementation(libs.ktor.client.websockets)
      implementation(libs.ktor.client.content.negotiation)
      implementation(libs.ktor.serialization.kotlinx.json)
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.serialization.json)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
      implementation(libs.ktor.client.mock)
    }
    androidMain.dependencies { implementation(libs.ktor.client.okhttp) }
    jvmMain.dependencies { implementation(libs.ktor.client.cio) }
    iosMain.dependencies { implementation(libs.ktor.client.darwin) }
  }
}
