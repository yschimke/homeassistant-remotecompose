import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  id("harc.base-conventions")
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.android.kmp.library)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

kotlin {
  jvmToolchain(21)
  android {
    namespace = "ee.schimke.terrazzo.ui.shared"
    compileSdk {
      version = release(libs.versions.android.compileSdk.get().toInt()) { minorApiLevel = 1 }
    }
    minSdk = libs.versions.android.minSdk.get().toInt()
    withHostTest {}
  }
  jvm()
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs {
    outputModuleName = "terrazzo"
    browser { commonWebpackConfig { outputFileName = "terrazzo.js" } }
    binaries.executable()
  }
  sourceSets {
    commonMain.dependencies {
      api(project(":ha-model"))
      api(project(":ha-client"))
      api(libs.cmp.runtime)
      api(libs.cmp.foundation)
      api(libs.cmp.ui)
      api(libs.cmp.material3)
      implementation(libs.cmp.navigation3.runtime)
      implementation(libs.cmp.navigation3.ui)
      implementation(libs.cmp.adaptive)
      implementation(libs.cmp.navigation.suite)
      api(libs.rc.player.compose)
      implementation(libs.kotlinx.coroutines.core)
      implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    }
    jvmMain.dependencies { implementation(compose.desktop.currentOs) }
    commonTest.dependencies { implementation(libs.kotlin.test) }
  }
}

compose.desktop {
  application {
    mainClass = "ee.schimke.terrazzo.desktop.MainKt"
    nativeDistributions {
      targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
      packageName = "Terrazzo"
      packageVersion = "1.0.0"
    }
  }
}
