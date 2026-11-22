package ee.schimke.ha.cards

import ee.schimke.livery.client.BindResult
import ee.schimke.livery.client.ContractBinder
import ee.schimke.livery.protocol.ActionKinds
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.DataSource
import ee.schimke.livery.protocol.ValueType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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

  /** Livery's own reading of a contract file: unknown keys are mistakes, not extensions. */
  private val contractJson = Json { ignoreUnknownKeys = false }

  private val contractDir = File("../card-designs/ui-builder/packs/contracts")

  /** The packs' contracts (the JSON files in `contracts/`), by reference (`ha-device/cover@1`). */
  private val contracts: Map<String, Contract> =
    contractDir
      .listFiles { file -> file.extension == "json" }
      .orEmpty()
      .map { contractJson.decodeFromString(Contract.serializer(), it.readText()) }
      .associateBy { it.ref }

  private fun contractOf(dir: File): Contract? =
    read(File(dir, "pack.json"))["contract"]?.jsonPrimitive?.content?.let(contracts::get)

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
    packDir
      .listFiles { file -> file.isDirectory && file.name != "contracts" }
      .orEmpty()
      .associateBy { it.name }

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

  /**
   * The readings (a number on a scale) and what they follow: an entity's `numeric_state` unless an
   * `attribute` names one of its domain's float bindings (HaLiveBindings.attributeFloats).
   */
  private val readings =
    setOf(
      "ha-cards/thermometer",
      "ha-cards/measurement",
      "ha-cards/arc-meter",
      "ha-cards/bar-meter",
    )

  /**
   * The float attributes [contract]'s entity has besides its state: `<domain>.s0.<attribute>`
   * inputs, by attribute. A cover's or a climate's state is a word, so a reading names one.
   */
  private fun attributeFloats(contract: Contract): Set<String> =
    contract.inputs
      .filter { (input, spec) ->
        spec.type == ValueType.Float &&
          input.count { it == '.' } == 2 &&
          input.split('.')[1] == "s0"
      }
      .keys
      .map { it.substringAfterLast('.') }
      .toSet()

  @Test
  fun aReadingOfACoverOrClimateFollowsOneOfItsFloats() {
    val problems = buildList {
      for ((name, dir) in packs) {
        val contract = contractOf(dir) ?: continue
        val floats = attributeFloats(contract)
        for (file in dir.listFiles { f -> f.extension == "uid" }.orEmpty().sortedBy { it.name }) {
          for ((id, element) in read(file)["nodes"]!!.jsonObject) {
            val node = element.jsonObject
            val component = node["componentId"]!!.jsonPrimitive.content
            val props = node["properties"]?.jsonObject.orEmpty()
            val domain = props["entityId"]?.literal()?.substringBefore('.') ?: continue
            if ("ha-device/$domain" != contract.id) continue
            val attribute = props["attribute"]?.literal()
            when {
              component in readings && attribute !in floats ->
                add(
                  "$name/${file.name} $id: a $domain reading follows `$attribute`, not one of $floats",
                )
              // A metric shows the entity's state text, which for these domains is a word (`heat`).
              component == "ha-cards/metric" ->
                add("$name/${file.name} $id: a metric shows a $domain's state, not a number")
            }
          }
        }
      }
    }
    assertEquals(emptyList(), problems)
  }

  @Test
  fun everyBoundValueIsAHostValueItsContractDeclares() {
    val types =
      mapOf(
        "string" to ValueType.String,
        "int" to ValueType.Int,
        "float" to ValueType.Float,
        "bool" to ValueType.Bool,
      )
    val problems = buildList {
      for ((name, dir) in packs) {
        val contract = contractOf(dir) ?: continue
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
          for ((variable, element) in declared) {
            val spec = element.jsonObject
            if (spec["persistence"]?.jsonPrimitive?.content != "host")
              add("$at: `$variable` is not a host value")
            val input = contract.inputs[variable]
            val type = types[spec["valueType"]?.jsonPrimitive?.content]
            when {
              input == null -> add("$at: `$variable` is no input of ${contract.ref}")
              input.type != type ->
                add("$at: `$variable` is ${input.type} in ${contract.ref}, not $type")
            }
          }
        }
      }
    }
    assertTrue(packs.isNotEmpty())
    assertEquals(emptyList(), problems)
  }

  @Test
  fun everyPackNamesAContractForItsDomain() {
    assertEquals(setOf("ha-device/climate@1", "ha-device/cover@1"), contracts.keys)
    val problems = buildList {
      for ((name, dir) in packs) {
        val ref = read(File(dir, "pack.json"))["contract"]?.jsonPrimitive?.content
        val contract = ref?.let(contracts::get)
        if (contract == null) {
          add("$name: pack.json's contract `$ref` is not in contracts/")
          continue
        }
        val domain =
          read(File(dir, "pack.json"))["audience"]!!
            .jsonObject["entity.domain"]!!
            .jsonPrimitive
            .content
        if (contract.id != "ha-device/$domain") add("$name: a $domain pack names ${contract.ref}")
      }
    }
    assertEquals(emptyList(), problems)
  }

  @Test
  fun theContractsAreOnesLiveryAccepts() {
    val builtIn =
      setOf(
        ActionKinds.OPEN_URL,
        ActionKinds.NAVIGATE,
        ActionKinds.DISMISS,
        ActionKinds.TRACK,
        ActionKinds.REFRESH,
        ActionKinds.SET,
      )
    val settable =
      setOf(ValueType.String, ValueType.Int, ValueType.Long, ValueType.Float, ValueType.Bool)
    val problems = buildList {
      for (contract in contracts.values) {
        val at = contract.ref
        if (contract.dataSource != DataSource.App) add("$at: the app fills it")
        for ((name, action) in contract.actions) {
          if (action.kind !in builtIn && !action.kind.startsWith("app:"))
            add("$at $name: kind `${action.kind}` is neither built in nor `app:`")
          if (action.kind == ActionKinds.SET) {
            val input = action.input?.let(contract.inputs::get)
            if (input == null) add("$at $name: a set names no input of the contract")
            else if (input.type !in settable) add("$at $name: a set can't carry ${input.type}")
            if (action.args.isNotEmpty()) add("$at $name: a set declares no args")
          } else if (action.input != null) add("$at $name: only a set names an input")
        }
        if (contract.examples.isEmpty()) add("$at: no examples")
        // Every example passes the gate every dataset does (PROTOCOL.md §4.1), and so does the
        // least a host can push: the required inputs alone, every optional one at its default.
        val least = buildJsonObject {
          contract.inputs.filterValues { it.required }.forEach { (input, _) -> put(input, "x") }
        }
        for ((example, data) in contract.examples + ("least" to least)) {
          val bound = ContractBinder.bind(contract, data)
          if (bound is BindResult.Invalid) add("$at example `$example`: ${bound.problems}")
        }
      }
    }
    assertEquals(emptyList(), problems)
  }
}
