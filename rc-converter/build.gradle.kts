plugins {
  id("harc.base-conventions")
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kmp.library)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

// Converters, card designs and templates are written once, in commonMain, against AndroidX's
// `remote-creation-compose` API, as `:rc-components` is. Android builds with AndroidX's own
// library and owns playback (the players, widgets, the app's caches); the JVM builds with
// rc-players' Compose Multiplatform fork, so a card design can be captured without Android.
kotlin {
  jvmToolchain(libs.versions.java.get().toInt())
  android {
    namespace = "ee.schimke.ha.rc"
    compileSdk {
      version = release(libs.versions.android.compileSdk.get().toInt()) { minorApiLevel = 1 }
    }
    minSdk = libs.versions.android.minSdk.get().toInt()
    withHostTest { isIncludeAndroidResources = true }
  }
  jvm()
  sourceSets {
    // The checked-in card designs (card-designs/ui-builder/designs/<cardType>.uid) migrated card
    // types draw their layout from (`CardDesigns`), packaged as `designs/<cardType>.uid`: one
    // source
    // for the app, the library, the design server and the scheduled import.
    commonMain {
      resources.srcDir(rootProject.layout.projectDirectory.dir("card-designs/ui-builder"))
    }
    commonMain.dependencies {
      api(project.dependencies.platform(libs.rc.players.bom))
      api(project(":rc-components"))
      implementation(project(":ha-model"))
      implementation(project(":ha-client"))
      // RcErrorBoundary: each player disables a document that throws, instead of the screen.
      implementation(project(":rc-player-ui"))
      implementation(libs.kotlinx.coroutines.core)
      // Compile-only, so Android consumers never see the fork beside AndroidX's.
      compileOnly(libs.rc.creation.compose)
      implementation(libs.cmp.runtime)
      implementation(libs.cmp.ui)
      implementation(libs.cmp.foundation)
      implementation(libs.cmp.material3)
      implementation(libs.cmp.material.icons.extended)
      // Compose Multiplatform player — the default for every card host (see `CardPlayback`).
      api(libs.rc.player.compose)
      // Bakes launcher-widget designs: writes a card's values into a values-free template.
      implementation(libs.rc.player.edit)
    }
    androidMain.dependencies {
      implementation(project.dependencies.platform(libs.compose.bom))
      // Tooling-preview annotation jar — `compose-preview render` (0.12.0)
      // fails fast on any Android+compose module that can't reach the
      // @Preview annotation class, even when the module has no @Preview
      // composables of its own.
      implementation(libs.compose.ui.tooling.preview)
      // RemoteCompose authoring + playback
      implementation(libs.remote.creation.compose)
      implementation(libs.remote.creation)
      implementation(libs.remote.creation.core)
      implementation(libs.remote.core)
      implementation(libs.remote.player.core)
      implementation(libs.remote.player.compose)
      implementation(libs.remote.player.view)
    }
    jvmMain.dependencies { implementation(libs.rc.creation.compose) }
    getByName("androidHostTest").dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlin.test.junit)
      implementation(libs.kotlinx.coroutines.test)
      implementation(libs.indriya)
      implementation(libs.ktor.client.mock)
      implementation("org.robolectric:robolectric:4.17")
      implementation(
        "com.google.android.apps.common.testing.accessibility.framework:accessibility-test-framework:4.1.1"
      )
      implementation(project.dependencies.platform(libs.compose.bom))
      implementation(libs.compose.ui.test.junit4)
      implementation(libs.compose.ui.test.manifest)
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

// Robolectric's SDK jar, resolved by Gradle (offline at test time) — same setup as
// `:ui-builder-catalog`. Used by the card design template end-to-end test, which captures a real
// document.
val robolectricSdk by configurations.creating {
  isCanBeConsumed = false
  isTransitive = false
}
val prepareRobolectricSdk by
  tasks.registering(Sync::class) {
    from(robolectricSdk)
    into(layout.buildDirectory.dir("robolectric-sdk"))
  }

tasks.withType<Test>().configureEach {
  inputs.files(robolectricSdk)
  // DesignedCardConvertersTest lists the checked-in card designs from disk.
  inputs.dir(rootProject.file("card-designs/ui-builder/designs")).withPropertyName("cardDesigns")
  dependsOn(prepareRobolectricSdk)
  systemProperty("robolectric.offline", "true")
  systemProperty(
    "robolectric.dependency.dir",
    layout.buildDirectory.dir("robolectric-sdk").get().asFile.absolutePath,
  )
  providers.gradleProperty("lovelaceRenderEvidence").orNull?.let {
    systemProperty("lovelaceRenderEvidence", file(it).absolutePath)
    outputs.upToDateWhen { false }
  }
  // The demo dashboards whose `mdi:` icons HaIconMapTest checks are all bundled.
  val demoDashboards =
    layout.projectDirectory.dir("../terrazzo-core/src/androidMain/resources/dashboards")
  inputs
    .dir(demoDashboards)
    .withPropertyName("demoDashboards")
    .withPathSensitivity(PathSensitivity.RELATIVE)
  systemProperty("harc.demoDashboards", demoDashboards.asFile.absolutePath)
  // `-PcardDesignsOut=<dir>` writes the spike's design-server files (manifest.json + blobs/)
  // there — see CardDesignTemplatesEndToEndTest.publishSpikeDesigns.
  providers.gradleProperty("cardDesignsOut").orNull?.let {
    systemProperty("cardDesignsOut", file(it).absolutePath)
    outputs.upToDateWhen { false }
  }
}

dependencies { robolectricSdk(libs.robolectric.sdk35) }

// CI runs `./gradlew test` across modules; a multiplatform module has no `test` of its own, so
// without this the converter tests would silently stop running there.
tasks.register("test") { dependsOn("testAndroidHostTest") }
