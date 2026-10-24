plugins {
  id("harc.base-conventions")
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kmp.library)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

// Cards are written once, in commonMain, against AndroidX's `remote-creation-compose` API. Android
// builds with AndroidX's own library, which is what the app's players and widgets capture with.
// The JVM builds with rc-players' Compose Multiplatform fork of it (same package, same API), so a
// card can be captured without Android. The few writer calls that differ are in
// RemoteCreationPlatform.
kotlin {
  jvmToolchain(libs.versions.java.get().toInt())
  android {
    namespace = "ee.schimke.ha.rc.components"
    compileSdk {
      version = release(libs.versions.android.compileSdk.get().toInt()) { minorApiLevel = 1 }
    }
    minSdk = libs.versions.android.minSdk.get().toInt()
    androidResources { enable = true }
    withHostTest {}
  }
  jvm()
  sourceSets {
    commonMain.dependencies {
      implementation(project.dependencies.platform(libs.rc.players.bom))
      implementation(libs.rc.player.protocol)
      // Compile-only, so Android consumers never see the fork beside AndroidX's.
      compileOnly(libs.rc.creation.compose)
      implementation(libs.cmp.runtime)
      implementation(libs.cmp.ui)
      implementation(libs.cmp.foundation)
      implementation(libs.cmp.material3)
      implementation(libs.cmp.material.icons.extended)
      api(libs.materialkolor)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.jetbrains.markdown)
    }
    androidMain.dependencies {
      implementation(project.dependencies.platform(libs.compose.bom))
      implementation(libs.compose.ui.text.google.fonts)
      implementation(libs.remote.creation.compose)
      implementation(libs.remote.creation)
      implementation(libs.remote.creation.core)
      implementation(libs.remote.core)
      implementation(libs.remote.foundation)
    }
    jvmMain.dependencies { implementation(libs.rc.creation.compose) }
    getByName("androidHostTest").dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlin.test.junit)
    }
  }
}

// The fork declares AndroidX's capabilities on Android, so the two can't both be on an Android
// classpath. This module's own Android compilation sees both (the fork through commonMain); it
// takes AndroidX's.
configurations.configureEach {
  resolutionStrategy.capabilitiesResolution {
    listOf(
        "androidx.compose.remote:remote-creation-compose",
        "androidx.compose.remote.foundation:foundation",
      )
      .forEach { capability ->
        withCapability(capability) {
          candidates
            .firstOrNull {
              (it.id as? ModuleComponentIdentifier)?.group?.startsWith("androidx.") == true
            }
            ?.let { select(it) }
        }
      }
  }
}

// CI runs `./gradlew test` across modules; a multiplatform module has no `test` of its own, so
// without this the card tests would silently stop running there.
tasks.register("test") { dependsOn("testAndroidHostTest") }
