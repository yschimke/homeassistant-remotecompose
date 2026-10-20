import com.android.build.api.dsl.ManagedVirtualDevice

// Baseline profile generator and macrobenchmarks (startup, dashboard scrolling) for `:app`.
//
//   Generate:  ./gradlew :app:generateBaselineProfile                 (connected emulator)
//              ./gradlew :app:generateBaselineProfile -Pbenchmark.gmd=true   (Gradle-managed)
//   Benchmark: ./gradlew :app-benchmark:connectedBenchmarkReleaseAndroidTest
//
// CI generates on an emulator in `.github/workflows/baseline-profiles.yml`.
plugins {
  id("harc.base-conventions")
  alias(libs.plugins.android.test)
  alias(libs.plugins.androidx.baselineprofile)
}

android {
  namespace = "ee.schimke.terrazzo.benchmark"
  compileSdk {
    version = release(libs.versions.android.compileSdk.get().toInt()) { minorApiLevel = 1 }
  }
  defaultConfig {
    // Same floor as `:app`.
    minSdk = 35
    targetSdk = libs.versions.android.targetSdk.get().toInt()
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  targetProjectPath = ":app"
  kotlin { jvmToolchain(libs.versions.java.get().toInt()) }
  testOptions.managedDevices.allDevices {
    create("pixel6Api35", ManagedVirtualDevice::class.java) {
      device = "Pixel 6"
      apiLevel = 35
      systemImageSource = "aosp"
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
  if (gmd) managedDevices += "pixel6Api35"
  useConnectedDevices = !gmd
}

dependencies {
  implementation(libs.androidx.test.ext.junit)
  implementation(libs.androidx.test.uiautomator)
  implementation(libs.androidx.benchmark.macro.junit4)
}
