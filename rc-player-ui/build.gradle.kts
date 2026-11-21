plugins {
  id("harc.base-conventions")
  id("harc.publishing")
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kmp.library)
  alias(libs.plugins.compose.compiler)
}

kotlin {
  jvmToolchain(21)
  android {
    namespace = "ee.schimke.terrazzo.player.shared"
    compileSdk {
      version = release(libs.versions.android.compileSdk.get().toInt()) { minorApiLevel = 2 }
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
      api(libs.cmp.ui)
      api(libs.rc.player.compose)
      implementation(libs.cmp.runtime)
      implementation(libs.cmp.foundation)
    }
    commonTest.dependencies { implementation(libs.kotlin.test) }
  }
}

mavenPublishing {
  pom {
    name.set("Remote Compose card player")
    description.set(
      "Play a Remote Compose card document with live named bindings, inside a Compose tree: Compose Multiplatform",
    )
  }
}
