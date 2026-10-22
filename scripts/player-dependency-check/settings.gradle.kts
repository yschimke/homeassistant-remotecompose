pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
  }
  versionCatalogs {
    create("libs") {
      from(files(providers.gradleProperty("catalogFile").orElse("../../gradle/libs.versions.toml")))
    }
  }
}

rootProject.name = "player-dependency-check"
