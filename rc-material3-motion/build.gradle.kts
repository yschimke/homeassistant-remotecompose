plugins {
  id("harc.base-conventions")
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kmp.library)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

// Material 3 motion for Remote Compose documents: spring and tween tokens the player runs, motion
// schemes, and the building blocks that move by them. Nothing here knows about Home Assistant, so
// the module can graduate to the rc-players family unchanged: keep `ha-*`, `rc-converter` and
// `rc-components` out of its dependencies. Written against rc-players' Compose Multiplatform
// creation API on every target, as `rc-components` is.
kotlin {
  jvmToolchain(libs.versions.java.get().toInt())
  android {
    namespace = "ee.schimke.rc.m3"
    compileSdk {
      version = release(libs.versions.android.compileSdk.get().toInt()) { minorApiLevel = 1 }
    }
    minSdk = libs.versions.android.minSdk.get().toInt()
    withHostTest {}
  }
  jvm()
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser() }
  iosArm64()
  iosSimulatorArm64()
  sourceSets {
    commonMain.dependencies {
      api(project.dependencies.platform(libs.rc.players.bom))
      api(libs.rc.creation.compose)
      implementation(libs.cmp.runtime)
      implementation(libs.cmp.ui)
    }
    androidMain.dependencies { implementation(project.dependencies.platform(libs.compose.bom)) }
    getByName("androidHostTest").dependencies {
      implementation(libs.remote.core)
      implementation(libs.kotlin.test)
      implementation(libs.kotlin.test.junit)
    }
  }
}

tasks.register("test") { dependsOn("testAndroidHostTest") }
