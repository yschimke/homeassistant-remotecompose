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

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    // Remote Compose (`androidx.compose.remote`), its Wear widget layer
    // (`androidx.wear.compose.remote`) and Glance Wear (`androidx.glance.wear`)
    // all resolve from Google Maven releases — no androidx.dev snapshot
    // repository, so nothing in the build depends on a post-submit build id
    // that ages out. The three groups still have to stay in lockstep; see the
    // note on `remote-compose` in `gradle/libs.versions.toml`.
    google()
    mavenCentral()
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
