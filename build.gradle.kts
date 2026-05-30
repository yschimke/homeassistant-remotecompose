import com.ncorti.ktfmt.gradle.KtfmtExtension

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
      classpath("com.google.firebase:firebase-crashlytics-gradle:3.0.6")
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
  alias(libs.plugins.ktfmt) apply false
}

allprojects {
  apply(plugin = "com.ncorti.ktfmt.gradle")
  extensions.configure<KtfmtExtension> { googleStyle() }

  // Unify every `org.hamcrest:*` coord on the 2.x unified jar. JUnit 4 and
  // Espresso still pull `hamcrest-core:1.3` (and the split `-library` /
  // `-integration` jars) transitively; their classes overlap with the
  // unified `org.hamcrest:hamcrest:2.2` that newer test deps resolve to,
  // tripping a duplicate-class check at android-test build time.
  configurations.all {
    resolutionStrategy.eachDependency {
      if (requested.group == "org.hamcrest") {
        useTarget("org.hamcrest:hamcrest:3.0")
        because("Unify hamcrest split 1.3 jars with the 2.x unified jar")
      }
    }
  }
}

// Wires .githooks/ as the repository's hooks directory, so the pre-commit
// ktfmt check runs locally. One-time bootstrap: `./gradlew installGitHooks`.
tasks.register<Exec>("installGitHooks") {
  group = "git hooks"
  description = "Configure git to use the .githooks directory in this repo."
  workingDir = rootDir
  commandLine("git", "config", "core.hooksPath", ".githooks")
}
