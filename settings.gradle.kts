pluginManagement {
  // Convention plugins (e.g. `harc.base-conventions`) live in this included
  // build. The shared ktfmt + dependency-hygiene setup that used to sit in a
  // root `allprojects {}` block is now a convention plugin each project
  // applies — one definition instead of cross-project root configuration.
  includeBuild("build-logic")
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

file("local.properties")
  .takeIf { it.isFile }
  ?.inputStream()
  ?.use { input -> java.util.Properties().apply { load(input) }.getProperty("androidchka.dir") }
  ?.trim()
  ?.takeIf { it.isNotEmpty() }
  ?.let { androidchkaDir ->
    apply(from = file(androidchkaDir).resolve("apply-androidchka.settings.gradle"))
  }

// AndroidX post-submit snapshot build the Remote Compose / Glance Wear artifacts
// are resolved from. Pinned to one build id rather than `snapshots/latest` so the
// build stays reproducible: a new snapshot only lands when this line changes.
// Browse builds at https://androidx.dev/snapshots/builds — a build id ages out
// after a few weeks, so if resolution starts 404-ing, bump to a fresh one.
val androidxSnapshotBuildId = "16113093"

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    // Remote Compose (`androidx.compose.remote`), its Wear widget layer
    // (`androidx.wear.compose.remote`) and Glance Wear (`androidx.glance.wear`)
    // track androidx-main snapshots instead of the Google Maven alphas: the
    // alphas publish too slowly for the APIs this repo builds on, and the three
    // groups only stay in lockstep when they come from the same build.
    // Scoped by group so nothing else can drift onto an unreviewed snapshot,
    // and `snapshotsOnly()` keeps release coordinates resolving from google().
    maven("https://androidx.dev/snapshots/builds/$androidxSnapshotBuildId/artifacts/repository") {
      name = "androidxSnapshots"
      content {
        includeGroupByRegex("androidx\\.compose\\.remote.*")
        includeGroupByRegex("androidx\\.wear\\.compose\\.remote.*")
        includeGroupByRegex("androidx\\.glance\\.wear.*")
      }
      mavenContent { snapshotsOnly() }
    }
  }
}

rootProject.name = "homeassistant-remotecompose"

include(
  ":ha-model",
  ":ha-client",
  ":rc-components",
  ":rc-components-ui",
  ":rc-converter",
  ":rc-card-shutter",
  ":rc-image-coil",
  ":previews",
  ":demo-app",
  ":terrazzo-core",
  ":app",
  ":wear",
  ":tv",
  ":integration",
  ":addon-server",
)
