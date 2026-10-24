package ee.schimke.ha.cards

import ee.schimke.ha.rc.design.CardDesigns
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Holds UI Builder's shelf (`ui-builder.policy.json`) to the card designs it edits
 * (`card-designs/ui-builder/designs/<cardType>.uid`) and the renderer that draws them
 * ([CardDesigns.components]): every component the renderer draws for a card design is on the shelf,
 * and every slot, property and value a checked-in design uses is one the shelf declares. A design
 * that gains a slot or a value the shelf can't place fails here, not in UI Builder.
 */
class CardLayoutPolicyTest {
  private val policy = Json.parseToJsonElement(File("ui-builder.policy.json").readText()).jsonObject
  private val components = policy["components"]!!.jsonObject
  private val builtins = policy["builtins"]!!.jsonObject

  private fun entry(id: String): JsonObject? = (components[id] ?: builtins[id]) as? JsonObject

  /**
   * A record's slot names: a component's `slotCapabilities`, the slots a canvas-mapped component
   * maps its canvas's onto (`canvasMapping.slots`), or a builtin's `slots`.
   */
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
      ?: this["slots"]?.jsonObject?.keys.orEmpty()

  private val designs: Map<String, JsonObject> =
    File("../card-designs/ui-builder/designs")
      .listFiles { file -> file.extension == "uid" }
      .orEmpty()
      .associate { it.name to Json.parseToJsonElement(it.readText()).jsonObject }

  @Test
  fun everyCardLayoutComponentIsOnTheShelf() {
    val missing = CardDesigns.components.keys.filter { entry(it) == null }
    assertEquals(emptyList(), missing.sorted(), "card layout components without a policy record")
  }

  @Test
  fun everySlotPropertyAndValueADesignUsesIsDeclared() {
    assertTrue(designs.isNotEmpty(), "card designs are checked in")
    val problems = buildList {
      for ((file, design) in designs) {
        for ((id, node) in design["nodes"]!!.jsonObject) {
          val component = node.jsonObject["componentId"]!!.jsonPrimitive.content
          val record = entry(component)
          if (record == null) {
            add("$file $id: `$component` has no policy record")
            continue
          }
          val slots = record.slotNames()
          node.jsonObject["slots"]?.jsonObject?.keys.orEmpty().forEach { slot ->
            if (slot !in slots) add("$file $id: `$component` declares no slot `$slot`")
          }
          val properties =
            record["propertyCapabilities"]?.jsonArray.orEmpty().associate {
              val property = it.jsonObject
              property["name"]!!.jsonPrimitive.content to
                property["allowedValues"]?.jsonArray?.map { v -> v.jsonPrimitive.content }
            }
          node.jsonObject["properties"]?.jsonObject.orEmpty().forEach { (name, value) ->
            if (name !in properties) {
              add("$file $id: `$component` declares no property `$name`")
              return@forEach
            }
            val literal = value.jsonObject["value"]?.jsonPrimitive?.contentOrNull
            val allowed = properties[name]
            if (allowed != null && literal !in allowed) {
              add("$file $id: `$component`.$name `$literal` is not one of $allowed")
            }
          }
        }
      }
    }
    assertEquals(emptyList(), problems)
  }
}
