import com.ncorti.ktfmt.gradle.FormattingOptionsBean
import com.ncorti.ktfmt.gradle.KtfmtExtension
import com.ncorti.ktfmt.gradle.tasks.KtfmtCheckTask
import com.ncorti.ktfmt.gradle.tasks.KtfmtFormatTask

buildscript {
  // Crashlytics is opt-in. Only put the Google Services / Crashlytics
  // Gradle plugins on the buildscript classpath when an
  // `app/google-services.json` is present — without it nothing here
  // resolves, so the default build is byte-for-byte unaffected and pulls
  // in no Firebase tooling. Resolve against `rootDir` (not a CWD-relative
  // `java.io.File`) so the check matches the app module's Gradle `file(…)`
  // lookup regardless of the launcher's working directory — e.g. the VS
  // Code / Android Studio Compose preview daemon starts Gradle from a
  // different CWD, which made this classpath silently drop out while the
  // app still applied the plugin, failing with "Plugin … not found".
  // Versions are only fetched by developers who have configured a key;
  // bump as needed.
  if (rootDir.resolve("app/google-services.json").exists()) {
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
  alias(libs.plugins.ktfmt) apply false
}

allprojects {
  apply(plugin = "com.ncorti.ktfmt.gradle")
  extensions.configure<KtfmtExtension> { googleStyle() }

  // ktfmt-gradle 0.26 wires its Android source-set tasks through the legacy
  // AGP `BaseExtension` / `AndroidSourceSet` API, which AGP 9 removed. The
  // failure is swallowed inside a `runCatching`, so `com.android.*` modules end
  // up with only their `*.kts` scripts validated and every `src/**/*.kt` file
  // silently unchecked (the lifecycle `ktfmtCheck` passes while formatting has
  // actually drifted). Until the plugin gains AGP-9 support, register the
  // missing Kotlin source-set tasks ourselves and hang them off the lifecycle
  // `ktfmtCheck` / `ktfmtFormat` tasks. KMP-Android modules already get their
  // common/jvm source sets from the multiplatform path, so only their
  // `android*` source sets need patching here.
  run {
    val project = this
    val ktfmtExt = extensions.getByType<KtfmtExtension>()

    fun registerAndroidKtfmt(taskSuffix: String, vararg includes: String) {
      if (project.tasks.findByName("ktfmtCheck$taskSuffix") != null) return
      val ktSources =
        project.fileTree("src") {
          includes.forEach { include(it) }
          exclude("**/build/**")
        }
      // Mirror whatever style the extension resolved to (currently googleStyle).
      // FormattingOptionsBean is Serializable, so build it eagerly to stay
      // configuration-cache friendly rather than capturing the extension.
      val options =
        FormattingOptionsBean(
          ktfmtExt.maxWidth.get(),
          ktfmtExt.blockIndent.get(),
          ktfmtExt.continuationIndent.get(),
          ktfmtExt.trailingCommaManagementStrategy.get(),
          ktfmtExt.removeUnusedImports.get(),
          ktfmtExt.debuggingPrintOpsAfterFormatting.get(),
        )
      val classpath = ktfmtExt.useClassloaderIsolation.get()
      val ktfmtClasspathCfg = project.configurations.named("ktfmt")
      val check =
        project.tasks.register<KtfmtCheckTask>("ktfmtCheck$taskSuffix") {
          setSource(ktSources)
          ktfmtClasspath.from(ktfmtClasspathCfg)
          formattingOptionsBean.set(options)
          includeOnly.set("")
          useClassloaderIsolation.set(classpath)
        }
      val format =
        project.tasks.register<KtfmtFormatTask>("ktfmtFormat$taskSuffix") {
          setSource(ktSources)
          ktfmtClasspath.from(ktfmtClasspathCfg)
          formattingOptionsBean.set(options)
          includeOnly.set("")
          useClassloaderIsolation.set(classpath)
        }
      project.tasks.named("ktfmtCheck") { dependsOn(check) }
      project.tasks.named("ktfmtFormat") { dependsOn(format) }
    }

    pluginManager.withPlugin("com.android.application") {
      registerAndroidKtfmt("AndroidSources", "**/*.kt")
    }
    pluginManager.withPlugin("com.android.library") {
      registerAndroidKtfmt("AndroidSources", "**/*.kt")
    }
    pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
      registerAndroidKtfmt("AndroidSources", "android*/**/*.kt")
    }
  }

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
