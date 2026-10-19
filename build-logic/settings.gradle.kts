pluginManagement {
  repositories {
    // Google Maven first, scoped to its own groups: androidx and AGP resolve there and never
    // reach Central or the plugin portal, which answer a miss with HTTP 429 under load instead
    // of the 404 that would have moved on.
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

dependencyResolutionManagement {
  repositories {
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
  // Reuse the main build's version catalog so plugin versions stay defined in
  // one place. Crucially, ktfmt and the Kotlin Gradle plugin resolve to the
  // same versions the modules use, which is what lets Gradle share a classloader
  // (see build.gradle.kts).
  versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}

rootProject.name = "build-logic"
