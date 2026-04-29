import com.ncorti.ktfmt.gradle.KtfmtExtension

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.android.kmp.library) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.compose.compiler) apply false
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
        useTarget("org.hamcrest:hamcrest:2.2")
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
