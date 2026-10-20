import com.android.build.api.dsl.ManagedVirtualDevice

// Baseline profile generator and macrobenchmarks (startup, list scrolling) for `:wear`.
//
//   Generate:  ./gradlew :wear:generateReleaseBaselineProfile               (connected emulator)
//              ./gradlew :wear:generateReleaseBaselineProfile -Pbenchmark.gmd=true
// (Gradle-managed)
//   Benchmark: ./gradlew :wear-benchmark:connectedBenchmarkReleaseAndroidTest
//
// CI generates on an emulator in `.github/workflows/baseline-profiles.yml`.
plugins {
  id("harc.base-conventions")
  alias(libs.plugins.android.test)
  alias(libs.plugins.androidx.baselineprofile)
}

android {
  namespace = "ee.schimke.terrazzo.wear.benchmark"
  compileSdk {
    version = release(libs.versions.android.compileSdk.get().toInt()) { minorApiLevel = 1 }
  }
  defaultConfig {
    // `:wear` allows 30; profile capture without root needs 33.
    minSdk = 33
    targetSdk = libs.versions.android.targetSdk.get().toInt()
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  targetProjectPath = ":wear"
  kotlin { jvmToolchain(libs.versions.java.get().toInt()) }
  testOptions.managedDevices.allDevices {
    create("wearSmallRoundApi34", ManagedVirtualDevice::class.java) {
      device = "Wear OS Small Round"
      apiLevel = 34
      systemImageSource = "android-wear"
    }
  }
}

// Only the plugin's non-debuggable variants make sense here: a macrobenchmark refuses a
// debuggable target, and root-level `connectedDebugAndroidTest` must not pick this module up.
androidComponents {
  beforeVariants { it.enable = it.buildType in setOf("benchmarkRelease", "nonMinifiedRelease") }
}

baselineProfile {
  val gmd = providers.gradleProperty("benchmark.gmd").orNull == "true"
  if (gmd) managedDevices += "wearSmallRoundApi34"
  useConnectedDevices = !gmd
}

dependencies {
  implementation(libs.androidx.test.ext.junit)
  implementation(libs.androidx.test.uiautomator)
  implementation(libs.androidx.benchmark.macro.junit4)
}
