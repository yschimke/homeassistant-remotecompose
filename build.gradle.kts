buildscript {
  // Crashlytics is opt-in. Only put the Google Services / Crashlytics
  // Gradle plugins on the buildscript classpath when an
  // `app/google-services.json` is present — without it nothing here
  // resolves, so the default build is byte-for-byte unaffected and pulls
  // in no Firebase tooling. The path is resolved relative to the root
  // project dir (Gradle's working directory). Versions are only fetched
  // by developers who have configured a key; bump as needed.
  if (java.io.File("app/google-services.json").exists()) {
    repositories {
      google()
      mavenCentral()
    }
    dependencies {
      classpath("com.google.gms:google-services:4.4.4")
      classpath("com.google.firebase:firebase-crashlytics-gradle:3.0.7")
    }
  }
}

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.android.kmp.library) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.play.publisher) apply false
  alias(libs.plugins.tapmoc) apply false
  // Shared ktfmt + dependency-hygiene conventions, also formatting this root
  // script's own `*.kts`. Every subproject applies the same convention plugin
  // (from the included `build-logic` build); it replaces the former root
  // `allprojects {}` block with a per-project convention plugin (cleaner, and
  // ready for Isolated Projects, which disallows that `allprojects {}` form).
  id("harc.base-conventions")
}

// Wires .githooks/ as the repository's hooks directory, so the pre-commit
// ktfmt check runs locally. One-time bootstrap: `./gradlew installGitHooks`.
tasks.register<Exec>("installGitHooks") {
  group = "git hooks"
  description = "Configure git to use the .githooks directory in this repo."
  workingDir = rootDir
  commandLine("git", "config", "core.hooksPath", ".githooks")
}
