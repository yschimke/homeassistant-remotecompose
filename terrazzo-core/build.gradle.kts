plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kmp.library)
  alias(libs.plugins.metro)
  alias(libs.plugins.tapmoc)
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  jvmToolchain(libs.versions.java.get().toInt())

  androidLibrary {
    namespace = "ee.schimke.terrazzo.core"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    // RemoteCompose widget needs API 35+; align with :app so Android
    // sources can reference the same platform APIs.
    minSdk = 35
  }

  sourceSets {
    commonMain.dependencies {
      implementation(project(":ha-model"))
      implementation(project(":ha-client"))
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.serialization.json)
    }
    androidMain.dependencies {
      implementation(libs.androidx.datastore.preferences)
      implementation(libs.appauth)
    }
  }
}

tapmoc {
  java(libs.versions.java.get().toInt())
  kotlin(libs.versions.kotlin.get())
}
