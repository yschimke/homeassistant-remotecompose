// `:device-pack-catalog` — the device packs (card-designs/ui-builder/packs/) as a Livery catalog.
//
// Each pack design is a UI Builder document on the `ha-cards` catalog. Livery's Gradle plugin
// captures them at build time with no UI Builder server (`capture { uiBuilder() }`), drawn by this
// repository's own renderer (`HaCardsUiBuilderCatalog`, over `CardDesignDocument`), and builds the
// catalog with `livery build --inspect`'s checks: every template reads only what its pack's
// contract declares. Nothing here is committed output: the catalog source tree is laid out from the
// packs by `devicePackSource`, and the `.rc` files are build output.
plugins {
  id("harc.base-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.livery.catalog)
}

kotlin { jvmToolchain(libs.versions.java.get().toInt()) }

dependencies {
  implementation(project(":ha-cards-catalog"))
  implementation(platform(libs.livery.bom))
  implementation(libs.livery.uibuilder.capture)
  implementation(libs.kotlinx.serialization.json)
  testImplementation(kotlin("test"))
}

// The packs as a Livery catalog source tree: `catalog.json`, the packs' contracts, and per pack
// design `designs/<designId>/design.json` (its contract, title and the pack's audience) beside its
// document as `in-app.uid`. Real-world designs are left out: their picture part
// (`picture-entity-card-image`) draws a sample camera entity, not the card's own, so it reads a
// value
// no contract declares.
val packs = rootProject.layout.projectDirectory.dir("card-designs/ui-builder/packs")
val devicePackSource by tasks.registering {
  description = "Lays the device packs out as a Livery catalog source tree"
  val packsDir = packs
  val out = layout.buildDirectory.dir("livery/source")
  inputs.dir(packsDir).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("packs")
  outputs.dir(out).withPropertyName("source")
  doLast {
    val root =
      out.get().asFile.apply {
        deleteRecursively()
        mkdirs()
      }
    val slurper = groovy.json.JsonSlurper()
    fun json(value: Any?) =
      groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(value)) + "\n"
    root
      .resolve("catalog.json")
      .writeText(json(mapOf("catalog" to "harc-device-packs", "channel" to "main")))
    packsDir.dir("contracts").asFile.copyRecursively(root.resolve("contracts"))
    packsDir.asFile
      .listFiles()
      .orEmpty()
      .filter { it.resolve("pack.json").isFile }
      .sortedBy { it.name }
      .forEach { pack ->
        @Suppress("UNCHECKED_CAST")
        val manifest = slurper.parse(pack.resolve("pack.json")) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        for (design in
          (manifest["designs"] as List<Map<String, Any?>>).filter { it["kind"] != "real-world" }) {
          val id = design["designId"] as String
          val dir = root.resolve("designs/$id").apply { mkdirs() }
          val audience = manifest["audience"] as? Map<*, *>
          dir
            .resolve("design.json")
            .writeText(
              json(
                buildMap {
                  put("id", id)
                  put("contract", manifest["contract"])
                  put("title", design["title"])
                  put("variant", "${design["kind"]}-${design["variant"]}")
                  if (audience != null) put("audience", listOf(mapOf("match" to audience)))
                },
              ),
            )
          pack.resolve(design["file"] as String).copyTo(dir.resolve("in-app.uid"))
        }
      }
  }
}

// The tests read the packs.
tasks.withType<Test>().configureEach {
  inputs.dir(packs).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("packs")
}

livery {
  source.set(layout.dir(devicePackSource.map { it.outputs.files.singleFile }))
  capture {
    uiBuilder()
    jvmArgs.add("-Djava.awt.headless=true")
  }
}
