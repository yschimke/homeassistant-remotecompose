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
  // The unit tests don't touch any Android-only API on the
  // CoilBitmapLoader paths under test (Bitmap, Canvas, Resources, …);
  // the only Android type referenced is `ContextWrapper`, which has a
  // pure-Java constructor. Defaults-on lets a future test reach into
  // an Android stub without NPE.
  testOptions { unitTests { isReturnDefaultValues = true } }
}

dependencies {
  // BitmapLoader interface lives in remote-player-core.
  api(libs.remote.player.core)

  implementation(libs.coil)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlin.test.junit)
}
