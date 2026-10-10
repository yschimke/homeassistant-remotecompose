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
      implementation(project(":rc-player-ui"))
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.ktor.client.core)
      implementation(libs.cmp.material.icons.core)
    }
    jvmMain.dependencies {
      implementation(compose.desktop.currentOs)
      implementation(libs.kotlinx.coroutines.swing)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
    }
  }
}

// Release jobs pass the tag version; local builds follow Android's release-please version.
val desktopVersion =
  providers
    .gradleProperty("desktopVersion")
    .orElse(
      providers
        .fileContents(rootProject.layout.projectDirectory.file("app/build.gradle.kts"))
        .asText
        .map { Regex("""val appVersionName = "([^"]+)"""").find(it)!!.groupValues[1] }
    )
    .get()

require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(desktopVersion)) {
  "desktopVersion must be MAJOR.MINOR.PATCH"
}

// jpackage requires a positive major on macOS. Offset the major on every OS
// to keep installer upgrades monotonic, including the eventual 1.0 release.
val installerVersion = desktopVersion.split(".").let { "${it[0].toInt() + 1}.${it[1]}.${it[2]}" }

compose.desktop {
  application {
    mainClass = "ee.schimke.terrazzo.desktop.MainKt"
    nativeDistributions {
      targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
      packageName = "Terrazzo"
      packageVersion = installerVersion
      includeAllModules = true
      description = "Terrazzo desktop client"
      vendor = "Yuri Schimke"
      macOS { bundleID = "ee.schimke.terrazzo.desktop" }
      windows {
        menu = true
        shortcut = true
        upgradeUuid = "6e4c949a-3f8d-4259-bdf4-fbb042453817"
      }
      linux { menuGroup = "Network" }
    }
  }
}
