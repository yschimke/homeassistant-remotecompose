plugins {
  id("harc.base-conventions")
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.android.kmp.library)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

// The room controls' shared half: which room, which controls, where pins go and the flat screen
// that shows them, free of any one headset's SDK. The phone/Android XR app (`:app`) supplies the
// entity cards and the spatial layer; a Quest app would supply its own on Meta's Spatial SDK.
kotlin {
  jvmToolchain(21)
  android {
    namespace = "ee.schimke.terrazzo.room"
    compileSdk {
      version = release(libs.versions.android.compileSdk.get().toInt()) { minorApiLevel = 1 }
    }
    minSdk = libs.versions.android.minSdk.get().toInt()
  }
  jvm()
  sourceSets {
    commonMain.dependencies {
      api(project(":ha-model"))
      api(libs.cmp.runtime)
      api(libs.cmp.foundation)
      api(libs.cmp.ui)
      api(libs.cmp.material3)
      implementation(libs.cmp.material.icons.core)
      implementation(libs.cmp.material.icons.extended)
      implementation(libs.kotlinx.serialization.json)
    }
    commonTest.dependencies { implementation(libs.kotlin.test) }
  }
}

// CI runs `./gradlew test` across modules; a multiplatform module has no `test` of its own, so
// without this the placement and pin tests would never run there.
tasks.register("test") { dependsOn("jvmTest") }
