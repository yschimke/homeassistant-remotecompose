pluginManagement {
  // Convention plugins (e.g. `harc.base-conventions`) live in this included
  // build. The shared ktfmt + dependency-hygiene setup that used to sit in a
  // root `allprojects {}` block is now a convention plugin each project
  // applies — one definition instead of cross-project root configuration.
  includeBuild("build-logic")
  repositories {
    gradlePluginPortal()
    google {
      mavenContent {
        includeGroupAndSubgroups("androidx")
        includeGroupAndSubgroups("com.android")
        includeGroupAndSubgroups("com.google")
      }
    }
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
  repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
  repositories {
    // Remote Compose (`androidx.compose.remote`), its Wear widget layer
    // (`androidx.wear.compose.remote`) and Glance Wear (`androidx.glance.wear`)
    // all resolve from Google Maven releases — no androidx.dev snapshot
    // repository, so nothing in the build depends on a post-submit build id
    // that ages out. The three groups still have to stay in lockstep; see the
    // note on `remote-compose` in `gradle/libs.versions.toml`.
    google {
      mavenContent {
        includeGroupAndSubgroups("androidx")
        includeGroupAndSubgroups("com.android")
        includeGroupAndSubgroups("com.google")
      }
    }
    mavenCentral()
    // Kotlin's browser toolchain adds temporary project repositories. Keep resolution
    // centralized here, including the Node and Yarn distributions it needs.
    ivy {
      name = "Binaryen distributions"
      url = uri("https://github.com/WebAssembly/binaryen/releases/download")
      patternLayout {
        artifact("version_[revision]/[artifact]-version_[revision]-[classifier].[ext]")
      }
      metadataSources { artifact() }
      content { includeGroup("com.github.webassembly") }
    }
    ivy {
      name = "Node distributions"
      url = uri("https://nodejs.org/dist")
      patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
      metadataSources { artifact() }
      content { includeGroup("org.nodejs") }
    }
    ivy {
      name = "Yarn distributions"
      url = uri("https://github.com/yarnpkg/yarn/releases/download")
      patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
      metadataSources { artifact() }
      content { includeGroup("com.yarnpkg") }
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
  ":card-designs",
  ":design-server",
  ":app-ui",
  ":rc-player-ui",
  ":ha-cards-catalog",
)

// The typed discovery API is released; the renderer SDK still uses a pinned source checkout.
val builderSource =
  providers
    .gradleProperty("composeUiBuilderDir")
    .orElse(providers.gradleProperty("uiBuilderSource"))

if (builderSource.isPresent || providers.gradleProperty("typedAdapterCatalog").orNull == "true") {
  require(builderSource.isPresent) {
    "Catalog tasks require -PcomposeUiBuilderDir=<pinned checkout>"
  }
  require(file(builderSource.get()).resolve("settings.gradle.kts").isFile) {
    "composeUiBuilderDir must point to a compose-ui-builder checkout"
  }
  includeBuild(builderSource.get())
  include(":ui-builder-catalog", ":ui-builder-catalog-host", ":ui-builder-catalog-consumer")
}

// Repository-owned UI Builder screen pilot; excluded from default catalog publication.
if (providers.gradleProperty("adaptiveUidPilot").orNull == "true") {
  include(":adaptive-uid-pilot")
}
