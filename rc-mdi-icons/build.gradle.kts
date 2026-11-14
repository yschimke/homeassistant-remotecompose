plugins {
  id("harc.base-conventions")
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kmp.library)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

// Material Design Icons for Remote Compose documents: the whole MDI catalog, by name, from
// `io.github.timoptr:mdi-icons` (generated from the pinned `@mdi/svg` release), as the
// `ImageVector`s cards bake with `toRemoteImageVector()` and as a `RemoteMdiIcon` composable. Home
// Assistant names every icon in MDI (`mdi:thermostat`), so anything that draws one of its icons
// resolves it here: `:rc-converter`'s `HaIconMap`, and `:rc-components` for inline icons.
kotlin {
  jvmToolchain(libs.versions.java.get().toInt())
  android {
    namespace = "ee.schimke.ha.rc.icons.mdi"
    compileSdk {
      version = release(libs.versions.android.compileSdk.get().toInt()) { minorApiLevel = 1 }
    }
    minSdk = libs.versions.android.minSdk.get().toInt()
  }
  jvm()
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser() }
  iosArm64()
  iosSimulatorArm64()
  sourceSets {
    commonMain.dependencies {
      api(project.dependencies.platform(libs.rc.players.bom))
      api(libs.mdi.icons)
      api(libs.cmp.ui)
      implementation(libs.cmp.runtime)
      api(libs.rc.creation.compose)
      api(libs.rc.creation.foundation)
    }
    jvmTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlin.test.junit)
    }
  }
}

// CI runs `./gradlew test` across modules; a multiplatform module has no `test` of its own.
tasks.register("test") { dependsOn("jvmTest") }
