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
  // bump as needed. `-Pharc.crashReporter=none` (or another backend) keeps
  // them off even with a key present. Mirrors selectCrashReporter() in
  // build-logic, which this block can't call; `:app` and `:wear` both use it.
  val crashReporter = providers.gradleProperty("harc.crashReporter").orNull?.trim()?.lowercase()
  if (
    rootDir.resolve("app/google-services.json").exists() &&
      (crashReporter == null || crashReporter == "auto" || crashReporter == "firebase")
  ) {
    // Declaring buildscript repositories makes the root `plugins {}` block
    // resolve from them too, so mirror settings' pluginManagement order:
    // the Play publisher plugin is only on the Plugin Portal, and without it
    // every keyed build failed at configuration.
    repositories {
      google()
      gradlePluginPortal()
      mavenCentral()
    }
    dependencies {
      classpath("com.google.gms:google-services:4.6.0")
      classpath("com.google.firebase:firebase-crashlytics-gradle:3.0.8")
    }
  }
}

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.android.kmp.library) apply false
  alias(libs.plugins.android.test) apply false
  alias(libs.plugins.androidx.baselineprofile) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.compose.multiplatform) apply false
  alias(libs.plugins.play.publisher) apply false
  alias(libs.plugins.tapmoc) apply false
  alias(libs.plugins.ksp) apply false
  // Shared ktfmt + dependency-hygiene conventions, also formatting this root
  // script's own `*.kts`. Every subproject applies the same convention plugin
  // (from the included `build-logic` build); it replaces the former root
  // `allprojects {}` block with a single per-project convention plugin.
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

abstract class VerifyYschimkeBomsTask : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val versionCatalog: RegularFileProperty

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val buildFiles: ConfigurableFileCollection

  @TaskAction
  fun verify() {
    val composeAiGroup = "ee.schimke.composeai"
    val catalogText = versionCatalog.get().asFile.readText()
    var inLibraries = false
    val bomsByCoordinate = mutableMapOf<String, String>()
    val libraries = mutableListOf<Triple<String, String, Boolean>>()

    // Retired pre-3.0 creation-fork coordinate, no longer in rc-players-bom >= 3.0.0;
    // pinned via rc-creation-fork until docs/architecture/rc-creation-migration.md completes.
    val legacyPinnedCoordinates = setOf("$composeAiGroup:third-party-remote-creation-compose")

    catalogText.lineSequence().forEachIndexed { index, rawLine ->
      val line = rawLine.substringBefore('#').trim()
      if (line.startsWith('[') && line.endsWith(']')) {
        inLibraries = line == "[libraries]"
        return@forEachIndexed
      }
      if (!inLibraries || '=' !in line) return@forEachIndexed
      val alias = line.substringBefore('=').trim()
      val rhs = line.substringAfter('=').trim()
      val coordinate =
        Regex("""module\s*=\s*"(ee\.schimke\.[^"]+)"""").find(rhs)?.groupValues?.get(1)
          ?: run {
            val group =
              Regex("""group\s*=\s*"(ee\.schimke\.[^"]+)"""").find(rhs)?.groupValues?.get(1)
            val name = Regex("""name\s*=\s*"([^"]+)"""").find(rhs)?.groupValues?.get(1)
            if (group != null && name != null) "$group:$name" else null
          }
          ?: return@forEachIndexed
      val hasVersion = Regex("""\bversion(?:\.ref)?\s*=""").containsMatchIn(rhs)
      if (coordinate.endsWith("-bom")) {
        check(hasVersion) {
          "gradle/libs.versions.toml:${index + 1}: BOM '$alias' ($coordinate) must declare version or version.ref."
        }
        bomsByCoordinate[coordinate] = alias
      } else if (coordinate in legacyPinnedCoordinates) {
        check(hasVersion) {
          "gradle/libs.versions.toml:${index + 1}: '$alias' ($coordinate) is not in rc-players-bom >= 3.0.0 and must declare version or version.ref."
        }
      } else {
        libraries += Triple(alias, coordinate, hasVersion)
      }
    }

    val contractArtifacts =
      setOf(
        "agent-grant-protocol",
        "common-io",
        "component-catalog-protocol",
        "daemon-bta",
        "daemon-devices",
        "daemon-protocol",
        "data-layoutinspector-core",
        "data-preview-overrides-core",
        "data-render-core",
        "data-theme-core",
        "design-guidelines-protocol",
        "parity-issues-protocol",
        "screen-document",
        "ui-builder-protocol",
      )
    val daemonArtifacts =
      setOf(
        "daemon-android",
        "daemon-client",
        "daemon-connector-api",
        "daemon-core",
        "daemon-desktop",
        "lottie-preview-runtime",
        "preview-annotations",
        "preview-data-api",
        "slot-preview-runtime",
        "svg-preview-runtime",
      )

    fun expectedBomFor(coordinate: String): String {
      val group = coordinate.substringBefore(':')
      val artifact = coordinate.substringAfter(':')
      check(group == composeAiGroup) {
        "Unmapped BOM for '$coordinate' in gradle/libs.versions.toml; declare and wire its BOM."
      }
      val bomArtifact =
        when {
          artifact.startsWith("rc-player-") ||
            artifact.startsWith("rc-widget-") ||
            artifact.startsWith("rc-creation-") ||
            artifact.startsWith("third-party-rc-embedded-player") ||
            artifact.startsWith("third-party-remote-creation-") ||
            artifact == "remote-compose-player-js-dist" -> "rc-players-bom"
          artifact.startsWith("livery-") -> "livery-bom"
          artifact.startsWith("compose-preview-ui-builder-") -> "compose-preview-ui-builder-bom"
          artifact in contractArtifacts -> "compose-preview-contracts-bom"
          artifact in daemonArtifacts ||
            artifact.startsWith("data-") ||
            artifact.startsWith("renderer-") -> "compose-preview-daemon-bom"
          else -> "compose-ai-tools-bom"
        }
      return "$composeAiGroup:$bomArtifact"
    }

    val requiredBomByLibAccessor = mutableMapOf<String, Pair<String, String>>()
    for ((alias, coordinate, hasVersion) in libraries) {
      val expectedBom = expectedBomFor(coordinate)
      check(!hasVersion) {
        "gradle/libs.versions.toml: '$alias' ($coordinate) must be versionless and resolved via $expectedBom."
      }
      val bomAlias =
        bomsByCoordinate[expectedBom]
          ?: error(
            "gradle/libs.versions.toml: '$alias' ($coordinate) requires '$expectedBom' in [libraries].",
          )
      val libAccessor = alias.replace('-', '.').replace('_', '.')
      val bomAccessor = bomAlias.replace('-', '.').replace('_', '.')
      requiredBomByLibAccessor[libAccessor] = expectedBom to bomAccessor
    }

    val inlineCoordRegex =
      Regex(""""(ee\.schimke\.[A-Za-z0-9._-]+:[A-Za-z0-9._-]+(?::[^"\s]+)?)\"""")
    val allowedInlineCoordinates = setOf("$composeAiGroup:ui-builder-renderer-sdk:0.0.0")

    for (file in buildFiles.files.sortedBy { it.path }) {
      val stripped =
        file
          .readText()
          .replace(Regex("""/\*[\s\S]*?\*/"""), "")
          .lineSequence()
          .map { it.substringBefore("//") }
          .joinToString("\n")

      for (match in inlineCoordRegex.findAll(stripped)) {
        val coord = match.groupValues[1]
        if (coord in allowedInlineCoordinates) continue
        error(
          "${file.path}: inline coordinate \"$coord\" is forbidden; declare versionless in gradle/libs.versions.toml and import its platform BOM.",
        )
      }

      for ((libAccessor, bomSpec) in requiredBomByLibAccessor) {
        val (expectedBom, bomAccessor) = bomSpec
        if (Regex("""\blibs\.${Regex.escape(libAccessor)}\b""").containsMatchIn(stripped)) {
          val importsBom =
            Regex("""platform\(\s*libs\.${Regex.escape(bomAccessor)}(?:\.get\(\))?\s*\)""")
              .containsMatchIn(stripped)
          check(importsBom) {
            "${file.path} uses libs.$libAccessor without importing platform(libs.$bomAccessor) ($expectedBom)."
          }
        }
      }
    }
  }
}

val verifyYschimkeBoms by
  tasks.registering(VerifyYschimkeBomsTask::class) {
    group = "verification"
    description = "Verifies that every ee.schimke.* dependency resolves versionless through a BOM."
    versionCatalog.set(layout.projectDirectory.file("gradle/libs.versions.toml"))
    buildFiles.from(
      fileTree(rootDir) {
        include("build.gradle.kts", "**/build.gradle.kts")
        exclude(
          "**/build/**",
          "**/.gradle/**",
          "**/node_modules/**",
          ".claude/**",
          ".worktrees/**",
        )
      },
    )
  }

subprojects {
  tasks
    .matching { it.name == "preBuild" || it.name == "check" }
    .configureEach { dependsOn(verifyYschimkeBoms) }
}
