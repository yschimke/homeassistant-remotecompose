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

tasks.named("check") {
  doLast {
    val repository = rootDir.resolve("../..")
    val catalog = repository.resolve("gradle/libs.versions.toml").readText()
    listOf("rc-player-compose", "rc-player-edit", "rc-player-protocol", "rc-player-js-dist")
      .forEach { alias ->
        val declaration = catalog.lineSequence().single { it.startsWith("$alias =") }
        check(!declaration.contains("version")) { "$alias must take its version from the BOM" }
      }
    val consumers =
      mapOf(
        "rc-components" to "implementation(platform(libs.rc.players.bom))",
        "rc-converter" to "api(platform(libs.rc.players.bom))",
        "rc-image-coil" to "api(platform(libs.rc.players.bom))",
        "rc-player-ui" to "api(project.dependencies.platform(libs.rc.players.bom))",
        "ui-builder-catalog-host" to "implementation(platform(libs.rc.players.bom))",
        "design-server" to "implementation(platform(libs.rc.players.bom))",
        "wear" to "implementation(platform(libs.rc.players.bom))",
      )
    consumers.forEach { (module, binding) ->
      check(repository.resolve("$module/build.gradle.kts").readText().contains(binding)) {
        "$module must import the player platform in its consuming scope"
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

    (players + listOf(embedPlayer)).forEach { configuration ->
      val result = configuration.incoming.resolutionResult
      val failures = result.allDependencies.filterIsInstance<UnresolvedDependencyResult>()
      check(failures.isEmpty()) { failures.joinToString("\n") { it.failure.message.orEmpty() } }
      val modules = result.allComponents.mapNotNull { it.id as? ModuleComponentIdentifier }
      val bom =
        result.allComponents.single {
          (it.id as? ModuleComponentIdentifier)?.module == "rc-players-bom"
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
      check(managed.isNotEmpty()) { "No player constraints were exercised" }
      check(managed.all { it.version == constraints.getValue(it.module) }) {
        "${configuration.name}: selected player versions differ from the BOM: $managed"
      }
      println("${configuration.name}: ${modules.filter { it.group == "ee.schimke.composeai" }}")
    }
    val zip = embedPlayer.singleFile
    check(zip.name.endsWith("-dist.zip")) { "Expected one browser distribution: $zip" }
    ZipFile(zip).use { check(it.getEntry("bundle.js") != null) }
  }
}
