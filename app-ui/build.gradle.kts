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
      implementation(compose.components.resources)
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
      implementation(libs.compose.webview)
      runtimeOnly(libs.jogl.runtime)
      runtimeOnly(libs.gluegen.runtime)
    }
    jvmTest.dependencies { implementation(libs.compose.multiplatform.ui.test.junit4) }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
      implementation(libs.ktor.client.mock)
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

// One version source for installed desktop builds and the web app, including tagged releases.
val feedbackBuildInfo =
  tasks.register("generateFeedbackBuildInfo") {
    val output = layout.buildDirectory.dir("generated/feedback/commonMain")
    val version = desktopVersion
    inputs.property("version", version)
    outputs.dir(output)
    doLast {
      output.get().file("ee/schimke/terrazzo/shared/FeedbackBuildInfo.kt").asFile.apply {
        parentFile.mkdirs()
        writeText(
          "package ee.schimke.terrazzo.shared\n\n" +
            "internal object FeedbackBuildInfo {\n  const val VERSION = \"$version\"\n}\n"
        )
      }
    }
  }

kotlin.sourceSets.commonMain { kotlin.srcDir(feedbackBuildInfo) }

require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(desktopVersion)) {
  "desktopVersion must be MAJOR.MINOR.PATCH"
}

// The Wasm web build as a Wasmo app (static resources only); see docs/architecture/wasmo-app.md.
// The directory form installs in Wasmo's dev mode; the archive is the distributable .wasmo file.
val wasmoVersion =
  desktopVersion.split(".").map(String::toLong).let { (major, minor, patch) ->
    major * 1_000_000 + minor * 1_000 + patch
  }
val wasmoAppDirectory =
  tasks.register<Sync>("wasmoAppDirectory") {
    val version = wasmoVersion
    inputs.property("version", version)
    into(layout.buildDirectory.dir("wasmo/dir/terrazzo.wasmo"))
    from("wasmo/wasmo-manifest.toml") { expand("version" to version) }
    into("www") {
      from(tasks.named("wasmJsBrowserDistribution"))
      from("wasmo/wasm-mime-fallback.js")
      filesMatching("index.html") {
        filter { line ->
          line.replace(
            "<script src=\"bootstrap.js\" defer></script>",
            "<script src=\"wasm-mime-fallback.js\"></script>\n  <script src=\"bootstrap.js\" defer></script>",
          )
        }
      }
    }
    into("www/assets") { from(rootProject.file("custom_components/terrazzo/brand/icon.png")) }
  }

tasks.register<Zip>("wasmoApp") {
  from(wasmoAppDirectory)
  archiveFileName = "terrazzo.wasmo"
  destinationDirectory = layout.buildDirectory.dir("wasmo")
}

// jpackage requires a positive major on macOS. Offset the major on every OS
// to keep installer upgrades monotonic, including the eventual 1.0 release.
val installerVersion = desktopVersion.split(".").let { "${it[0].toInt() + 1}.${it[1]}.${it[2]}" }

compose.desktop {
  application {
    mainClass = "ee.schimke.terrazzo.desktop.MainKt"
    jvmArgs +=
      listOf(
        "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.peer=ALL-UNNAMED",
      )
    if (System.getProperty("os.name").contains("Mac")) {
      jvmArgs +=
        listOf(
          "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
          "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
        )
    }
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
