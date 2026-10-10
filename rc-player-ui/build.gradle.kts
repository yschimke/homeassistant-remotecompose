plugins {
  id("harc.base-conventions")
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kmp.library)
  alias(libs.plugins.compose.compiler)
}

kotlin {
  jvmToolchain(21)
  android {
    namespace = "ee.schimke.terrazzo.player.shared"
    compileSdk {
      version = release(libs.versions.android.compileSdk.get().toInt()) { minorApiLevel = 1 }
    }
    minSdk = libs.versions.android.minSdk.get().toInt()
    withHostTest {}
  }
  jvm()
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser() }
  sourceSets {
    commonMain.dependencies {
      api(libs.cmp.ui)
      api(libs.rc.player.compose)
      implementation(libs.cmp.runtime)
      implementation(libs.cmp.foundation)
    }
    commonTest.dependencies { implementation(libs.kotlin.test) }
  }
}
