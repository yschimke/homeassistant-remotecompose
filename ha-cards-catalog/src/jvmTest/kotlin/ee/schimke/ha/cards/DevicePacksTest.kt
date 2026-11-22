package ee.schimke.ha.cards

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Holds the device packs (`card-designs/ui-builder/packs/<device>/`, docs/design/device-packs.md)
 * to the shelf UI Builder edits them with (`ui-builder.policy.json`) and to the bindings a host
 * pushes, so a pack that names a component, slot, property, value or binding the builder or the
 * host doesn't have fails here rather than on ha.coo.ee.
 */
class DevicePacksTest {
  private val policy = Json.parseToJsonElement(File("ui-builder.policy.json").readText()).jsonObject
  private val components = policy["components"]!!.jsonObject
  private val builtins = policy["builtins"]!!.jsonObject

  /**
   * The frame's own layouts (remote-m3's `frame/widget-host`, which has no record in this policy):
   * the collapsible ones a card uses, as the design server's seeder places them, with their slots
   * and the properties a pack may set.
   */
  private val frameLayouts =
    mapOf(
      "layout/collapsible-column" to
        setOf(
          "verticalArrangement",
          "verticalSpacingDp",
          "horizontalAlignment",
          "alignment",
          "weight",
        ),
      "layout/collapsible-row" to
        setOf("horizontalArrangement", "horizontalSpacingDp", "verticalAlignment"),
    )

  private val frameEnums =
    mapOf(
      "verticalArrangement" to
        listOf("top", "center", "bottom", "spaceBetween", "spaceAround", "spaceEvenly"),
      "horizontalArrangement" to
        listOf("start", "center", "end", "spaceBetween", "spaceAround", "spaceEvenly"),
      "horizontalAlignment" to listOf("start", "center", "end"),
      "verticalAlignment" to listOf("top", "center", "bottom"),
    )

  /** The modifiers a card design uses (see CardDesign.MODIFIERS). */
  private val modifiers = setOf("fillMaxWidth", "fillMaxHeight", "fillMaxSize")

  /**
   * The named values a host pushes for a pack's entity (HaLiveBindings), plus the alert text the
   * alert rule supplies. A pack binds `<domain>.s0.<suffix>`: its one entity, slot 0.
   */
  private val bindings =
    mapOf(
      "cover" to
        setOf(
          "name",
          "state",
          "state_int",
          "is_on",
          "is_available",
          "current_position",
          "supported_features",
          "last_changed",
        ),
      "climate" to
        setOf(
          "name",
          "state",
          "is_on",
          "is_available",
          "current_temperature",
          "target_temperature",
          "last_changed",
        ),
      "alert" to setOf("message", "detail"),
    )

  private val kinds = setOf("controls", "alert", "toolbar", "real-world")

  private fun record(id: String): JsonObject? = (components[id] ?: builtins[id]) as? JsonObject

  private fun JsonObject.slotNames(): Set<String> =
    this["slotCapabilities"]
      ?.jsonArray
      ?.map { it.jsonObject["name"]!!.jsonPrimitive.content }
      ?.toSet()
      ?: (this["canvasMapping"] as? JsonObject)
        ?.get("slots")
        ?.jsonObject
        ?.values
        ?.map { it.jsonPrimitive.content }
        ?.toSet()
      ?: (this["insertContent"] as? JsonObject)?.get("slots")?.jsonObject?.keys
      ?: this["slots"]?.jsonObject?.keys.orEmpty()

  private val packDir = File("../card-designs/ui-builder/packs")

  private val packs: Map<String, File> =
    packDir.listFiles { file -> file.isDirectory }.orEmpty().associateBy { it.name }

  private fun read(file: File): JsonObject = Json.parseToJsonElement(file.readText()).jsonObject

  private fun JsonElement.literal(): String? =
    (this as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull

  @Test
  fun everyPackListsExactlyItsDesignsWithTheirIds() {
    assertEquals(setOf("garage-door", "thermostat"), packs.keys)
    val index = read(File(packDir, "index.json"))["packs"]!!.jsonArray.map { it.jsonObject }
    assertEquals(
      packs.keys.sorted().map { "$it/pack.json" },
      index.map { it["manifest"]!!.jsonPrimitive.content },
      "index.json lists every pack's manifest",
    )
    val problems = buildList {
      for ((name, dir) in packs) {
        val manifest = read(File(dir, "pack.json"))
        if (manifest["id"]?.jsonPrimitive?.content != name) add("$name: pack.json id is not $name")
        val listed = manifest["designs"]!!.jsonArray.map { it.jsonObject }
        val files = dir.listFiles { f -> f.extension == "uid" }.orEmpty().map { it.name }.toSet()
        if (listed.map { it["file"]!!.jsonPrimitive.content }.toSet() != files)
          add("$name: pack.json lists ${listed.map { it["file"] }}, the folder has $files")
        for (entry in listed) {
          val file = File(dir, entry["file"]!!.jsonPrimitive.content)
          if (!file.isFile) continue
          val kind = entry["kind"]!!.jsonPrimitive.content
          if (kind !in kinds) add("$name/${file.name}: kind `$kind` is not one of $kinds")
          if (read(file)["id"]?.jsonPrimitive?.content != entry["designId"]?.jsonPrimitive?.content)
            add("$name/${file.name}: its id is not pack.json's designId")
          if (kind == "alert" && entry["showWhen"]?.jsonArray.isNullOrEmpty())
            add("$name/${file.name}: an alert says when it shows")
        }
        for (kind in kinds) if (listed.none { it["kind"]!!.jsonPrimitive.content == kind })
          add("$name: no `$kind` design")
      }
    }
    assertEquals(emptyList(), problems)
  }

  @Test
  fun everyDesignIsOneTreeOfShelfComponentsWithDeclaredValues() {
    val problems = buildList {
      for ((name, dir) in packs) {
        for (file in dir.listFiles { f -> f.extension == "uid" }.orEmpty().sortedBy { it.name }) {
          val at = "$name/${file.name}"
          val design = read(file)
          if (design["catalogPin"]!!.jsonObject["systemId"]!!.jsonPrimitive.content != "ha-cards")
            add("$at: not on the ha-cards catalog")
          val nodes = design["nodes"]!!.jsonObject
          val roots = design["roots"]!!.jsonArray.map { it.jsonPrimitive.content }
          if (roots.size != 1) add("$at: ${roots.size} roots")
          if (
            nodes[roots.first()]?.jsonObject?.get("componentId")?.jsonPrimitive?.content !=
              "ha-cards/dashboard-card"
          )
            add("$at: the root is not a dashboard card")
          val reached = mutableSetOf<String>()
          fun walk(id: String) {
            if (!reached.add(id)) {
              add("$at: $id is placed twice")
              return
            }
            val node = nodes[id]?.jsonObject
            if (node == null) {
              add("$at: missing node $id")
              return
            }
            node["slots"]?.jsonObject?.values?.forEach { children ->
              (children as JsonArray).forEach { walk(it.jsonPrimitive.content) }
            }
          }
          roots.forEach(::walk)
          (nodes.keys - reached).forEach { add("$at: $it is not in the tree") }
          for ((id, element) in nodes) {
            val node = element.jsonObject
            val component = node["componentId"]!!.jsonPrimitive.content
            node["modifiers"]?.jsonArray?.forEach { m ->
              val type = m.jsonObject["type"]!!.jsonPrimitive.content
              if (type !in modifiers)
                add("$at $id: modifier `$type` is not one a card design draws")
            }
            val frame = frameLayouts[component]
            if (frame != null) {
              node["slots"]
                ?.jsonObject
                ?.keys
                .orEmpty()
                .filter { it != "children" }
                .forEach { add("$at $id: `$component` has no slot `$it`") }
              node["properties"]?.jsonObject.orEmpty().forEach { (prop, value) ->
                if (prop !in frame) add("$at $id: `$component` has no property `$prop`")
                frameEnums[prop]?.let { allowed ->
                  if (value.literal() !in allowed)
                    add("$at $id: $prop `${value.literal()}` is not one of $allowed")
                }
              }
              continue
            }
            val rec = record(component)
            if (rec == null || rec.containsKey("excluded")) {
              add("$at $id: `$component` is not on the shelf")
              continue
            }
            val slots = rec.slotNames()
            node["slots"]?.jsonObject?.keys.orEmpty().forEach {
              if (it !in slots) add("$at $id: `$component` declares no slot `$it`")
            }
            val declared =
              rec["propertyCapabilities"]?.jsonArray.orEmpty().associate {
                it.jsonObject["name"]!!.jsonPrimitive.content to
                  it.jsonObject["allowedValues"]?.jsonArray?.map { v -> v.jsonPrimitive.content }
              }
            node["properties"]?.jsonObject.orEmpty().forEach { (prop, value) ->
              if (prop !in declared) {
                add("$at $id: `$component` declares no property `$prop`")
                return@forEach
              }
              val allowed = declared[prop]
              if (allowed != null && value.literal() !in allowed)
                add("$at $id: `$component`.$prop `${value.literal()}` is not one of $allowed")
            }
          }
        }
      }
    }
    assertEquals(emptyList(), problems)
  }

  @Test
  fun everyBoundValueIsDeclaredLiveAndOneAHostPushes() {
    val problems = buildList {
      for ((name, dir) in packs) {
        for (file in dir.listFiles { f -> f.extension == "uid" }.orEmpty().sortedBy { it.name }) {
          val at = "$name/${file.name}"
          val design = read(file)
          val declared = design["stateVariables"]!!.jsonObject
          val used = mutableSetOf<String>()
          design["nodes"]!!.jsonObject.values.forEach { node ->
            node.jsonObject["properties"]?.jsonObject?.values?.forEach { value ->
              val v = value.jsonObject
              if (v["type"]?.jsonPrimitive?.content == "state")
                used += v["variable"]!!.jsonPrimitive.content
            }
          }
          (used - declared.keys).forEach { add("$at: binds undeclared `$it`") }
          for ((variable, spec) in declared) {
            if (spec.jsonObject["persistence"]?.jsonPrimitive?.content != "host")
              add("$at: `$variable` is not a host value")
            val parts = variable.split('.')
            val domain = parts.first()
            val suffix = parts.last()
            val known = bindings[domain]
            when {
              known == null -> add("$at: `$variable` is no binding of a pack entity")
              domain != "alert" && (parts.size != 3 || parts[1] != "s0") ->
                add("$at: `$variable` isn't the pack entity's slot 0")
              suffix !in known -> add("$at: `$variable`: a host pushes no `$suffix` for $domain")
            }
          }
        }
      }
    }
    assertTrue(packs.isNotEmpty())
    assertEquals(emptyList(), problems)
  }
}
