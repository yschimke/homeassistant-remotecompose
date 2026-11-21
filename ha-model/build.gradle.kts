plugins {
  id("harc.base-conventions")
  id("harc.publishing")
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.android.kmp.library)
}

kotlin {
  jvmToolchain(libs.versions.java.get().toInt())

  android {
    namespace = "ee.schimke.ha.model"
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
      api(libs.kotlinx.serialization.json)
      api(libs.kotlinx.datetime)
    }
    commonTest.dependencies { implementation(libs.kotlin.test) }
  }
}

// CI runs `./gradlew test` across modules; a multiplatform module has no `test` of its own, so
// without this the model's tests (room ranking, state formatting, templates) never run there.
tasks.register("test") { dependsOn("jvmTest") }

mavenPublishing {
  pom {
    name.set("Home Assistant model")
    description.set(
      "Home Assistant dashboard, card config and state snapshot types, with state formatting and Jinja: Kotlin Multiplatform, no networking",
    )
  }
}
