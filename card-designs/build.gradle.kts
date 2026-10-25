plugins {
  id("harc.base-conventions")
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kmp.library)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

// Generates the card design library the design server hosts: every template shape the bundled demo
// dashboards produce, for each theme, with preview renders and a browsable catalog
// (CardDesignLibrary), in either of two ways:
// - `generateCardDesigns`, on the JVM with no Android: the Compose Multiplatform creation fork and
//   player, into `build/card-designs/` (`-PcardDesignsOut=<dir>`). This is the library the design
//   server publishes, captured as it captures a key its library lacks (DesignGenerator);
// - `generateCardDesignsAndroid`, on Android: a Robolectric test capturing with AndroidX, as the
//   app does, into `build/card-designs-android/` (`-PcardDesignsAndroidOut=<dir>`), to check the
//   JVM's against (`checkCardDesignParity`).
// JvmLibraryAndroidPlaybackTest plays a sample of the published library in the app's own player:
// `./gradlew :card-designs:generateCardDesigns` then
// `./gradlew :card-designs:testAndroidHostTest --tests '*JvmLibraryAndroidPlaybackTest'`.
// See docs/architecture/card-design-server.md.
kotlin {
  jvmToolchain(libs.versions.java.get().toInt())
  android {
    namespace = "ee.schimke.ha.designs"
    compileSdk {
      version = release(libs.versions.android.compileSdk.get().toInt()) { minorApiLevel = 1 }
    }
    minSdk = libs.versions.android.minSdk.get().toInt()
    withHostTest { isIncludeAndroidResources = true }
  }
  jvm()
  sourceSets {
    // The demo dashboards the app bundles (`dashboards/<name>/`): the library's source cards.
    commonMain {
      resources.srcDir(
        rootProject.layout.projectDirectory.dir("terrazzo-core/src/androidMain/resources")
      )
    }
    commonMain.dependencies {
      implementation(project.dependencies.platform(libs.rc.players.bom))
      implementation(project(":ha-model"))
      implementation(project(":ha-client"))
      implementation(project(":rc-converter"))
      implementation(project(":rc-components"))
      // Card designs authored in UI Builder (`ui-builder/designs/*.uid`), drawn by the catalog's
      // own components: CardDesignDocument.
      implementation(project(":ha-cards-catalog"))
      // Compile-only, so Android consumers never see the fork beside AndroidX's.
      compileOnly(libs.rc.creation.compose)
      implementation(libs.cmp.runtime)
      implementation(libs.kotlinx.serialization.json)
    }
    jvmMain.dependencies {
      implementation(libs.rc.creation.compose)
      implementation(libs.rc.player.compose)
      implementation(libs.cmp.ui)
      implementation(libs.cmp.foundation)
      implementation(compose.desktop.currentOs)
    }
    getByName("androidHostTest").dependencies {
      implementation(project(":rc-player-ui"))
      // CommunityFamilyEvidenceTest renders the sticker sheet's community previews.
      implementation(project(":previews"))
      implementation(project.dependencies.platform(libs.compose.bom))
      implementation(libs.compose.ui)
      implementation(libs.compose.foundation)
      implementation(libs.remote.creation.core)
      implementation(libs.remote.creation)
      implementation(libs.compose.ui.test.junit4)
      implementation(libs.compose.ui.test.manifest)
      implementation(libs.kotlin.test)
      implementation(libs.kotlin.test.junit)
      implementation("org.robolectric:robolectric:4.17")
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

// Robolectric's SDK jar, resolved by Gradle (offline at test time) — same setup as `:rc-converter`.
val robolectricSdk by configurations.creating {
  isCanBeConsumed = false
  isTransitive = false
}
val prepareRobolectricSdk by
  tasks.registering(Sync::class) {
    from(robolectricSdk)
    into(layout.buildDirectory.dir("robolectric-sdk"))
  }

// The published library, generated on the JVM.
val cardDesignsOut: File =
  providers.gradleProperty("cardDesignsOut").orNull?.let { file(it) }
    ?: layout.buildDirectory.dir("card-designs").get().asFile

// Android's library, apart from the JVM's so the two can be compared (CardDesignLibraryParity).
val cardDesignsAndroidOut: File =
  providers.gradleProperty("cardDesignsAndroidOut").orNull?.let { file(it) }
    ?: layout.buildDirectory.dir("card-designs-android").get().asFile

tasks
  .withType<Test>()
  .matching { it.name == "testAndroidHostTest" }
  .configureEach {
    inputs.files(robolectricSdk)
    // The published sticker-sheet selection is part of the preview discovery contract.
    inputs.file(rootProject.file("catalog.spec.json"))
    inputs.dir(layout.projectDirectory.dir("ui-builder/designs"))
    dependsOn(prepareRobolectricSdk)
    systemProperty("robolectric.offline", "true")
    systemProperty(
      "robolectric.dependency.dir",
      layout.buildDirectory.dir("robolectric-sdk").get().asFile.absolutePath,
    )
    systemProperty("cardDesignsAndroidOut", cardDesignsAndroidOut.absolutePath)
    outputs.dir(cardDesignsAndroidOut)
    // The published library JvmLibraryAndroidPlaybackTest plays, when it has been generated.
    systemProperty("cardDesignsJvmDir", cardDesignsOut.absolutePath)
    inputs.files(fileTree(cardDesignsOut) { include("manifest.json") }).optional()
    mustRunAfter("generateCardDesigns")
    maxHeapSize = "2g"
  }

tasks.register("generateCardDesignsAndroid") {
  group = "card designs"
  description =
    "Generates the card design library into $cardDesignsAndroidOut, on Android (Robolectric)."
  dependsOn("testAndroidHostTest")
}

tasks.register<JavaExec>("generateCardDesigns") {
  group = "card designs"
  description = "Generates the card design library into $cardDesignsOut, on the JVM."
  val main = kotlin.jvm().compilations.getByName("main")
  classpath(main.output.allOutputs, main.runtimeDependencyFiles)
  mainClass.set("ee.schimke.ha.designs.JvmCardDesignLibraryKt")
  // CheckedInCardDesigns reads ui-builder/designs relative to the module.
  workingDir = layout.projectDirectory.asFile
  inputs.dir(layout.projectDirectory.dir("ui-builder/designs"))
  outputs.dir(cardDesignsOut)
  args(cardDesignsOut.absolutePath)
  maxHeapSize = "2g"
}

// Whether the two libraries draw the same, key by key (CardDesignLibraryParity): both drawings of
// any difference land in build/card-design-parity/.
tasks.register<JavaExec>("checkCardDesignParity") {
  group = "card designs"
  description = "Checks the Android and JVM card design libraries draw the same."
  dependsOn("generateCardDesignsAndroid", "generateCardDesigns")
  val main = kotlin.jvm().compilations.getByName("main")
  classpath(main.output.allOutputs, main.runtimeDependencyFiles)
  mainClass.set("ee.schimke.ha.designs.CardDesignLibraryParityKt")
  args(
    cardDesignsAndroidOut.absolutePath,
    cardDesignsOut.absolutePath,
    layout.buildDirectory.dir("card-design-parity").get().asFile.absolutePath,
  )
  maxHeapSize = "2g"
}

dependencies { robolectricSdk(libs.robolectric.sdk35) }

// CI runs `./gradlew test` across modules; a multiplatform module has no `test` of its own, so
// without this the library's tests (and its generation) would silently stop running there.
tasks.register("test") { dependsOn("testAndroidHostTest") }
