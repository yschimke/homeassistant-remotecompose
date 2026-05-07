plugins { alias(libs.plugins.android.library) }

android {
  namespace = "ee.schimke.ha.rc.image"
  compileSdk = libs.versions.android.compileSdk.get().toInt()
  defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
  compileOptions {
    sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
  }
  kotlin { jvmToolchain(libs.versions.java.get().toInt()) }
}

dependencies {
  // BitmapLoader interface lives in remote-player-core.
  api(libs.remote.player.core)

  implementation(libs.coil)

  testImplementation(libs.kotlin.test)
}
