package ee.schimke.ha.designserver

import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Pins the embed's JavaScript port of the slot bindings (`embed/ha-card.js`) to ha-model: every
 * case in `embed/test/bindings-golden.json` must carry exactly the bindings
 * [CardDesignSlots.slotBindings] derives. `embed/test/bindings.test.mjs` checks the port against
 * the same file, so the two can only agree through it.
 *
 * After changing a case, rewrite the expectations with `./gradlew :design-server:test --tests
 * '*EmbedBindingsGoldenTest*' -Pembed.golden.update=true`.
 */
class EmbedBindingsGoldenTest {
  private val file =
    File(System.getProperty("embed.golden") ?: "../embed/test/bindings-golden.json")
  private val json = Json { prettyPrint = true }

  @Test
  fun portMatchesHaModel() {
    val golden = Json.parseToJsonElement(file.readText()).jsonObject
    val cases = golden.getValue("cases").jsonArray.map { it.jsonObject }
    val computed = cases.map { case -> JsonObject(case + ("expected" to expected(case))) }

    if (System.getProperty("embed.golden.update") == "true") {
      val updated = JsonObject(golden + ("cases" to kotlinx.serialization.json.JsonArray(computed)))
      file.writeText(json.encodeToString(JsonElement.serializer(), updated) + "\n")
      return
    }
    for ((case, actual) in cases.zip(computed)) {
      assertEquals(
        actual["expected"],
        case["expected"],
        "bindings for '${case["name"]}' (rerun with -Pembed.golden.update=true after a deliberate change)",
      )
    }
  }

  private fun expected(case: JsonObject): JsonObject {
    val card = case.getValue("card").jsonObject
    val states =
      case.getValue("states").jsonObject.mapValues { (id, value) ->
        val state = value.jsonObject
        EntityState(
          entityId = id,
          state = state.getValue("state").jsonPrimitive.content,
          attributes = state["attributes"]?.jsonObject ?: JsonObject(emptyMap()),
        )
      }
    val config = CardConfig(card["type"]?.jsonPrimitive?.contentOrNull.orEmpty(), card)
    val bindings = CardDesignSlots.slotBindings(config, HaSnapshot(states = states))
    return buildJsonObject {
      put("strings", JsonObject(bindings.strings.mapValues { JsonPrimitive(it.value) }))
      put("booleans", JsonObject(bindings.booleans.mapValues { JsonPrimitive(it.value) }))
      put("ints", JsonObject(bindings.ints.mapValues { JsonPrimitive(it.value) }))
      put(
        "floats",
        JsonObject(
          bindings.floats.mapValues { (_, v) ->
            if (v.isNaN()) JsonPrimitive("NaN") else JsonPrimitive(v.toDouble())
          }
        ),
      )
    }
  }
}
