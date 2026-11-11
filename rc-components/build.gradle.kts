plugins {
  id("harc.base-conventions")
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kmp.library)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

// Cards are written once, in commonMain, against rc-players' Compose Multiplatform creation API
// (`ee.schimke.composeai.rccreation`), and captured with it on every target, Android included.
// AndroidX's own `remote-creation-compose` is not a dependency here: it stays where the platform
// needs it (Glance Wear, Wear `remote-material3`), in the modules that use those.
kotlin {
  jvmToolchain(libs.versions.java.get().toInt())
  android {
    namespace = "ee.schimke.ha.rc.components"
    compileSdk {
      version = release(libs.versions.android.compileSdk.get().toInt()) { minorApiLevel = 1 }
    }
    minSdk = libs.versions.android.minSdk.get().toInt()
    androidResources { enable = true }
    withHostTest {}
  }
  jvm()
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser() }
  iosArm64()
  iosSimulatorArm64()
  // Skia encodes and decodes images on every target but Android: `nonAndroidMain`.
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
  applyDefaultHierarchyTemplate {
    common {
      group("nonAndroid") {
        withJvm()
        withWasmJs()
        withIosArm64()
        withIosSimulatorArm64()
      }
    }
  }
  sourceSets {
    commonMain.dependencies {
      implementation(project.dependencies.platform(libs.rc.players.bom))
      implementation(libs.rc.player.protocol)
      // Finishes a capture for its player (CaptureProfile): a launcher's document is rewritten into
      // what the launcher decodes.
      implementation(libs.rc.player.edit)
      api(project(":rc-material3-motion"))
      api(libs.rc.creation.compose)
      api(libs.rc.creation.foundation)
      implementation(libs.cmp.runtime)
      implementation(libs.cmp.ui)
      implementation(libs.cmp.foundation)
      implementation(libs.cmp.material3)
      implementation(libs.cmp.material.icons.extended)
      api(libs.materialkolor)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.jetbrains.markdown)
    }
    androidMain.dependencies {
      implementation(project.dependencies.platform(libs.compose.bom))
      implementation(libs.compose.ui.text.google.fonts)
      // `Limits`, to let documents with URL images parse (RemoteImageSupport).
      implementation(libs.remote.core)
      // `Rc.AndroidColors`: the system colour resource ids a themed colour names (SystemHaTheme).
      implementation(libs.remote.creation.core)
    }
    getByName("androidHostTest").dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlin.test.junit)
    }
  }
}

// CI runs `./gradlew test` across modules; a multiplatform module has no `test` of its own, so
// without this the card tests would silently stop running there.
tasks.register("test") { dependsOn("testAndroidHostTest") }
