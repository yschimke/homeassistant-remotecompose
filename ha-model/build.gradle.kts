plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.android.kmp.library)
}

kotlin {
  jvmToolchain(libs.versions.java.get().toInt())

  androidLibrary {
    namespace = "ee.schimke.ha.model"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    minSdk = libs.versions.android.minSdk.get().toInt()
  }
  jvm()

  sourceSets {
    commonMain.dependencies {
      api(libs.kotlinx.serialization.json)
      api(libs.kotlinx.datetime)
    }
    commonTest.dependencies { implementation(libs.kotlin.test) }
  }
}
