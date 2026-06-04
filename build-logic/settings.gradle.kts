pluginManagement {
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
  // Reuse the main build's version catalog so plugin versions stay defined in
  // one place. Crucially, ktfmt and the Kotlin Gradle plugin resolve to the
  // same versions the modules use, which is what lets Gradle share a classloader
  // (see build.gradle.kts).
  versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}

rootProject.name = "build-logic"
