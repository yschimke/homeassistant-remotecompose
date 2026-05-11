pluginManagement {
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
