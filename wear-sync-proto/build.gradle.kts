plugins {
  id("harc.base-conventions")
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
}

// The Wear ↔ phone sync schema, shared by `:app` and `:wear` so DataItem payloads and
// MessageClient frames always decode on the peer. It used to be two byte-for-byte copies kept in
// step by hand. `src/main/proto/wear_sync.proto` documents the wire format; the @Serializable
// classes encode to the same proto3 bytes through kotlinx-serialization-protobuf.

android {
  namespace = "ee.schimke.terrazzo.wearsync.proto"
  compileSdk {
    version = release(libs.versions.android.compileSdk.get().toInt()) { minorApiLevel = 2 }
  }
  defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
  compileOptions {
    sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
  }
  kotlin { jvmToolchain(libs.versions.java.get().toInt()) }
}

dependencies {
  // In the public API: DataStore `Serializer`s, Data Layer `DataClient` / `Asset` / `DataMap`, and
  // the `ProtoBuf` instance both peers encode with.
  api(libs.androidx.datastore)
  api(libs.play.services.wearable)
  api(libs.kotlinx.serialization.protobuf)
  implementation(libs.kotlinx.coroutines.core)
}
