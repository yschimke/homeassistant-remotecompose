plugins {
  id("harc.base-conventions")
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kmp.library)
  alias(libs.plugins.metro)
  alias(libs.plugins.tapmoc)
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  jvmToolchain(libs.versions.java.get().toInt())

  android {
    namespace = "ee.schimke.terrazzo.core"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    // RemoteCompose widget needs API 35+; align with :app so Android
    // sources can reference the same platform APIs.
    minSdk = 35
    withHostTest {}
  }

  sourceSets {
    commonMain.dependencies {
      implementation(project(":ha-model"))
      implementation(project(":ha-client"))
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.kotlinx.serialization.protobuf)
    }
    androidMain.dependencies {
      implementation(libs.androidx.datastore)
      implementation(libs.androidx.datastore.preferences)
      implementation(libs.appauth)
      implementation(libs.ktor.client.okhttp)
    }
  }
}

tapmoc {
  // Java 17 bytecode (class-file v61) so the JDK-17 preview render daemon
  // (Robolectric, preview.coo.ee) can load this module's classes; the build
  // toolchain stays on JDK 21 (jvmToolchain above). v65 (JDK 21) bytecode
  // throws UnsupportedClassVersionError in the render daemon.
  java(17)
  kotlin(libs.versions.kotlin.get())
}
