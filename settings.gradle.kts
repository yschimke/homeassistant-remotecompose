pluginManagement {
  // Convention plugins (e.g. `harc.base-conventions`) live in this included
  // build. The shared ktfmt + dependency-hygiene setup that used to sit in a
  // root `allprojects {}` block is now a convention plugin each project
  // applies — one definition instead of cross-project root configuration.
  includeBuild("build-logic")
  repositories {
    // Google Maven ahead of the plugin portal, so AGP's plugin dependencies never
    // reach the portal (a Maven Central proxy), whose HTTP 429 under load fails
    // the build where a 404 would have moved on.
    google {
      mavenContent {
        includeGroupAndSubgroups("androidx")
        includeGroupAndSubgroups("com.android")
        includeGroupAndSubgroups("com.google")
      }
    }
    gradlePluginPortal()
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

// BuildFetch remote Gradle build cache, beside the local one (`org.gradle.caching=true`).
// Off until `harc.buildFetchCacheUrl` in gradle.properties names this repository's BuildFetch
// project AND a token resolves, so a checkout without either builds exactly as before.
// Writes only from trusted CI (`ON_CI=true`, which `.github/actions/buildfetch-cache` sets on
// pushes to main); PRs and developer machines read. The token is the first non-blank of the
// project-specific `BUILDFETCH_HARC_GRADLE_REMOTE_CACHE_TOKEN` and the general
// `BUILDFETCH_GRADLE_REMOTE_CACHE_TOKEN` (CI exports the general one), each as an env var first
// and a Gradle property second, so one ~/.gradle/gradle.properties can hold a token per project.
// Same scheme as compose-ai-tools: docs/build-scripts/SETTINGS.md#build-cache there.
val nonBlank = { source: Provider<String> -> source.map { it.trim() }.filter { it.isNotEmpty() } }
val buildFetchCacheUrl = nonBlank(providers.gradleProperty("harc.buildFetchCacheUrl")).orNull
val buildFetchCacheToken =
  nonBlank(providers.environmentVariable("BUILDFETCH_HARC_GRADLE_REMOTE_CACHE_TOKEN"))
    .orElse(nonBlank(providers.gradleProperty("BUILDFETCH_HARC_GRADLE_REMOTE_CACHE_TOKEN")))
    .orElse(nonBlank(providers.environmentVariable("BUILDFETCH_GRADLE_REMOTE_CACHE_TOKEN")))
    .orElse(nonBlank(providers.gradleProperty("BUILDFETCH_GRADLE_REMOTE_CACHE_TOKEN")))
    .orNull
val onCi = providers.environmentVariable("ON_CI").orElse("false").get().toBoolean()

buildCache {
  // The local cache stays on everywhere, trusted pushes included: it suppresses only the
  // redundant remote pushes, and setup-gradle's restored copy is a frozen snapshot on CI anyway.
  local { isEnabled = true }
  if (buildFetchCacheUrl != null) {
    remote<HttpBuildCache> {
      url = uri(buildFetchCacheUrl)
      credentials {
        username = "token-auth"
        password = buildFetchCacheToken
      }
      isPush = onCi
      isEnabled = buildFetchCacheToken != null
    }
  }
}

// `-PaddonServerOnly=true` includes just the Home Assistant add-on server and the two modules it
// depends on. The add-on image (`addon-server/addon/Dockerfile`) copies only those directories, and
// Gradle 9 refuses to configure an included project whose directory is missing. None of the three
// needs an Android SDK to build their JVM targets.
if (providers.gradleProperty("addonServerOnly").orNull == "true") {
  include(":ha-model", ":ha-client", ":addon-server")
} else {
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
    ":app-benchmark",
    ":wear",
    ":wear-benchmark",
    ":tv",
    ":integration",
    ":addon-server",
    ":card-designs",
    ":design-server",
    ":app-ui",
    ":rc-player-ui",
    ":ha-cards-catalog",
  )
}

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
