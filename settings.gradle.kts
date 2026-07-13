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
    google()
    mavenCentral()
  }
}

// --- BuildFetch remote build cache (https://buildfetch.com) -----------------
// The cache URL is not secret and may be committed; supply it via the
// `buildfetch.cache.url` Gradle property or the BUILDFETCH_GRADLE_CACHE_URL env
// var. The write token IS secret: supply it via the `buildfetch.token` Gradle
// property (e.g. in ~/.gradle/gradle.properties) or the BUILDFETCH_GRADLE_TOKEN
// env var (a CI secret). The remote cache is skipped entirely until a URL is
// present, so contributors without access build normally. Only CI on `main`
// pushes new entries; PRs, local builds and AI agents read only.
val buildFetchCacheUrl =
  providers.gradleProperty("buildfetch.cache.url")
    .orElse(providers.environmentVariable("BUILDFETCH_GRADLE_CACHE_URL"))
    .orNull
val buildFetchToken =
  providers.gradleProperty("buildfetch.token")
    .orElse(providers.environmentVariable("BUILDFETCH_GRADLE_TOKEN"))
    .orNull
val buildFetchOnCi = providers.environmentVariable("CI").orNull == "true"
val buildFetchOnMain = providers.environmentVariable("GITHUB_REF").orNull == "refs/heads/main"

buildCache {
  if (!buildFetchCacheUrl.isNullOrBlank()) {
    remote<HttpBuildCache> {
      url = uri(buildFetchCacheUrl)
      isEnabled = true
      isPush = buildFetchOnCi && buildFetchOnMain && !buildFetchToken.isNullOrBlank()
      if (!buildFetchToken.isNullOrBlank()) {
        credentials {
          username = "token-auth"
          password = buildFetchToken
        }
      }
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
