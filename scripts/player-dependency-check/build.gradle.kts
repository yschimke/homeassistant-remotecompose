import java.util.zip.ZipFile
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins { alias(libs.plugins.kotlin.multiplatform) }

// Register Kotlin's variant compatibility rules without configuring Android or a native compiler.
kotlin { jvm() }

val targets =
  mapOf(
    "android" to KotlinPlatformType.androidJvm,
    "desktop" to KotlinPlatformType.jvm,
    "wasm" to KotlinPlatformType.wasm,
    "iosArm64" to KotlinPlatformType.native,
    "iosSimulatorArm64" to KotlinPlatformType.native,
  )

val players = targets.map { (target, platformType) ->
  configurations.create("${target}Players") {
    isCanBeConsumed = false
    attributes {
      attribute(KotlinPlatformType.attribute, platformType)
      attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
      attribute(
        Usage.USAGE_ATTRIBUTE,
        objects.named(
          when (platformType) {
            KotlinPlatformType.wasm -> "kotlin-runtime"
            KotlinPlatformType.native -> "kotlin-api"
            else -> Usage.JAVA_RUNTIME
          }
        ),
      )
      attribute(
        TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
        objects.named(
          when (platformType) {
            KotlinPlatformType.androidJvm -> TargetJvmEnvironment.ANDROID
            KotlinPlatformType.jvm -> TargetJvmEnvironment.STANDARD_JVM
            else -> "non-jvm"
          }
        ),
      )
      if (platformType == KotlinPlatformType.wasm) {
        attribute(Attribute.of("org.jetbrains.kotlin.wasm.target", String::class.java), "js")
      }
      if (platformType == KotlinPlatformType.native) {
        attribute(
          Attribute.of("org.jetbrains.kotlin.native.target", String::class.java),
          if (target == "iosArm64") "ios_arm64" else "ios_simulator_arm64",
        )
      }
    }
    dependencies.add(project.dependencies.platform(libs.rc.players.bom.get()))
    dependencies.add(libs.rc.player.compose.get())
    dependencies.add(libs.rc.player.edit.get())
    dependencies.add(libs.rc.player.protocol.get())
  }
}

// Exercise the server's standalone, non-transitive ZIP configuration too.
// Keep platform traversal enabled; only the ZIP dependency is non-transitive.
val embedPlayer = configurations.create("embedPlayer")

dependencies {
  embedPlayer(platform(libs.rc.players.bom))
  embedPlayer(libs.rc.player.js.dist) {
    isTransitive = false
    artifact {
      classifier = "dist"
      extension = "zip"
      type = "zip"
    }
  }
}

fun jvmRuntimeConfiguration(name: String, configure: Configuration.() -> Unit): Configuration =
  configurations.create(name) {
    isCanBeConsumed = false
    attributes {
      attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
      attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
      attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
      attribute(
        TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
        objects.named(TargetJvmEnvironment.STANDARD_JVM),
      )
    }
    configure()
  }

val nonPlayerBomConfigurations =
  mapOf(
    "compose-preview-contracts-bom" to
      jvmRuntimeConfiguration("contractsBomCheck") {
        dependencies.add(project.dependencies.platform(libs.composeai.contracts.bom.get()))
        dependencies.add(libs.composeai.data.render.core.get())
      },
    "compose-preview-daemon-bom" to
      jvmRuntimeConfiguration("daemonBomCheck") {
        dependencies.add(project.dependencies.platform(libs.composeai.daemon.bom.get()))
        dependencies.add(libs.composeai.preview.annotations.get())
        dependencies.add(libs.composeai.preview.overrides.get())
      },
    "compose-ai-tools-bom" to
      jvmRuntimeConfiguration("toolsBomCheck") {
        dependencies.add(project.dependencies.platform(libs.composeai.tools.bom.get()))
        dependencies.add(libs.composeai.preview.discovery.get())
      },
    "compose-preview-ui-builder-bom" to
      jvmRuntimeConfiguration("uiBuilderBomCheck") {
        dependencies.add(project.dependencies.platform(libs.composeai.ui.builder.bom.get()))
        dependencies.add(libs.composeai.ui.builder.runtime.get())
      },
  )

tasks.named("check") {
  doLast {
    val repository = rootDir.resolve("../..")
    val catalog = repository.resolve("gradle/libs.versions.toml").readText()
    var inLibraries = false
    catalog.lineSequence().forEachIndexed { index, raw ->
      val line = raw.substringBefore('#').trim()
      if (line.startsWith('[') && line.endsWith(']')) {
        inLibraries = line == "[libraries]"
        return@forEachIndexed
      }
      if (!inLibraries || !line.contains("\"ee.schimke.")) return@forEachIndexed
      val alias = line.substringBefore('=').trim()
      val rhs = line.substringAfter('=').trim()
      if (
        rhs.contains("-bom\"") ||
          rhs.contains("\"ee.schimke.composeai:third-party-remote-creation-compose\"")
      ) {
        check(rhs.contains("version")) {
          "gradle/libs.versions.toml:${index + 1}: $alias must declare a version"
        }
      } else {
        check(!rhs.contains("version")) {
          "gradle/libs.versions.toml:${index + 1}: $alias must take its version from a BOM"
        }
      }
    }
    val consumers =
      mapOf(
        "app" to "debugImplementation(platform(libs.composeai.daemon.bom))",
        "card-designs" to "implementation(project.dependencies.platform(libs.rc.players.bom))",
        "ha-cards-catalog" to
          "implementation(project.dependencies.platform(libs.composeai.contracts.bom))",
        "previews" to "implementation(platform(libs.composeai.contracts.bom))",
        "rc-components" to "api(project.dependencies.platform(libs.rc.players.bom))",
        "rc-converter" to "api(project.dependencies.platform(libs.rc.players.bom))",
        "rc-image-coil" to "api(platform(libs.rc.players.bom))",
        "rc-material3-motion" to "api(project.dependencies.platform(libs.rc.players.bom))",
        "rc-player-ui" to "api(project.dependencies.platform(libs.rc.players.bom))",
        "ui-builder-catalog" to "testImplementation(platform(libs.composeai.tools.bom))",
        "ui-builder-catalog-consumer" to "implementation(platform(libs.composeai.ui.builder.bom))",
        "ui-builder-catalog-host" to "implementation(platform(libs.rc.players.bom))",
        "design-server" to "implementation(platform(libs.rc.players.bom))",
        "wear" to "implementation(platform(libs.rc.players.bom))",
      )
    consumers.forEach { (module, binding) ->
      check(repository.resolve("$module/build.gradle.kts").readText().contains(binding)) {
        "$module must import its platform BOM in its consuming scope"
      }
    }
    check(
      repository
        .resolve("design-server/build.gradle.kts")
        .readText()
        .contains("embedPlayer(platform(libs.rc.players.bom))")
    ) {
      "The standalone ZIP configuration must import the platform"
    }

    val bomChecks =
      (players + listOf(embedPlayer)).map { "rc-players-bom" to it } +
        nonPlayerBomConfigurations.map { (bomModule, config) -> bomModule to config }

    bomChecks.forEach { (bomModule, configuration) ->
      val result = configuration.incoming.resolutionResult
      val failures = result.allDependencies.filterIsInstance<UnresolvedDependencyResult>()
      check(failures.isEmpty()) { failures.joinToString("\n") { it.failure.message.orEmpty() } }
      val modules = result.allComponents.mapNotNull { it.id as? ModuleComponentIdentifier }
      val bom =
        result.allComponents.single {
          (it.id as? ModuleComponentIdentifier)?.module == bomModule
        }
      val constraints =
        bom.dependencies
          .filter { it.isConstraint }
          .associate {
            val requested = it.requested as ModuleComponentSelector
            requested.module to requested.version
          }
      val managed = modules.filter {
        it.group == "ee.schimke.composeai" && it.module in constraints
      }
      check(managed.isNotEmpty()) { "No $bomModule constraints were exercised" }
      check(managed.all { it.version == constraints.getValue(it.module) }) {
        "${configuration.name}: selected versions differ from $bomModule: $managed"
      }
      println("${configuration.name}: ${modules.filter { it.group == "ee.schimke.composeai" }}")
    }
    val zip = embedPlayer.singleFile
    check(zip.name.endsWith("-dist.zip")) { "Expected one browser distribution: $zip" }
    ZipFile(zip).use { check(it.getEntry("bundle.js") != null) }
  }
}
