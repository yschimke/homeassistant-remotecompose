package ee.schimke.ha.designserver

import ee.schimke.ha.model.EntityState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The `ha-cards` catalog's starting templates (`ha-cards-catalog/ui-builder/designs/`), generated
 * from card schemas with [HaCardsSeed] so they stay what the design server would seed.
 *
 * Fails when a committed template differs from what the seed now writes. Regenerate with `./gradlew
 * :design-server:test --tests '*HaCardsTemplatesTest*' -PhaCardsTemplates.update=true` and commit
 * the result.
 *
 * Also holds the templates to the catalog's policy: each is listed in `templates`, pinned to
 * `ha-cards`, and written only in ids the policy declares (or the builder's own `layout/`
 * vocabulary), with icon keys the `icon` property allows.
 */
class HaCardsTemplatesTest {
  private val catalogDir = File(System.getProperty("haCards.dir") ?: "../ha-cards-catalog")
  private val update = System.getProperty("haCards.update") == "true"
  private val json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
  }

  private fun state(id: String, state: String, vararg attributes: Pair<String, Any>) =
    id to
      EntityState(
        id,
        state,
        buildJsonObject {
          for ((k, v) in attributes) when (v) {
            is Number -> put(k, v)
            else -> put(k, v.toString())
          }
        },
      )

  private fun card(raw: String) = Json.parseToJsonElement(raw).jsonObject

  private val lightTile =
    CardSchema.fromCard(
      card("""{"type": "tile", "entity": "light.kitchen"}"""),
      mapOf(state("light.kitchen", "on", "friendly_name" to "Kitchen")),
    )

  private val livingRoom =
    CardSchema.fromCard(
      card(
        """{"type": "entities", "title": "Living room",
           "entities": ["light.living_room", "switch.fan", "sensor.living_room_temperature"]}"""
      ),
      mapOf(
        state("light.living_room", "on", "friendly_name" to "Ceiling light"),
        state("switch.fan", "off", "friendly_name" to "Fan"),
        state(
          "sensor.living_room_temperature",
          "21.5",
          "friendly_name" to "Temperature",
          "unit_of_measurement" to "°C",
          "device_class" to "temperature",
        ),
      ),
    )

  private val humidity =
    CardSchema.fromCard(
      card("""{"type": "gauge", "entity": "sensor.humidity", "min": 0, "max": 100}"""),
      mapOf(
        state(
          "sensor.humidity",
          "46",
          "friendly_name" to "Humidity",
          "unit_of_measurement" to "%",
          "device_class" to "humidity",
        )
      ),
    )

  private val fan =
    CardSchema.fromCard(
      card("""{"type": "button", "entity": "switch.fan"}"""),
      mapOf(state("switch.fan", "on", "friendly_name" to "Fan")),
    )

  /** Every template, by id, in the order the policy lists them. */
  private val templates: Map<String, JsonObject> =
    linkedMapOf(
      "card-light-tile" to
        HaCardsSeed.haCard(
          lightTile,
          "card-light-tile",
          "Light tile",
          HaCardsSeed.card("card-light-tile", heightDp = 96),
        ),
      "card-entities" to
        UiBuilderSeed.document(
          livingRoom,
          "card-entities",
          "Entities",
          HaCardsSeed.card("card-entities"),
        ),
      "card-gauge" to
        HaCardsSeed.haCard(
          humidity,
          "card-gauge",
          "Gauge",
          HaCardsSeed.card("card-gauge", heightDp = 220),
        ),
      "wear-light-small" to
        UiBuilderSeed.document(
          lightTile,
          "wear-light-small",
          "Light · Wear widget, Small",
          HaCardsSeed.wearSmall("wear-light-small"),
        ),
      "wear-entities-large" to
        UiBuilderSeed.document(
          livingRoom,
          "wear-entities-large",
          "Entities · Wear widget, Large",
          HaCardsSeed.wearLarge("wear-entities-large"),
        ),
      "launcher-fan-2x2" to
        HaCardsSeed.haCard(
          fan,
          "launcher-fan-2x2",
          "Fan button · launcher 2x2",
          HaCardsSeed.launcher("launcher-fan-2x2", widthDp = 130, heightDp = 220),
        ),
      "launcher-entities-4x2" to
        HaCardsSeed.haCard(
          livingRoom,
          "launcher-entities-4x2",
          "Entities · launcher 4x2",
          HaCardsSeed.launcher("launcher-entities-4x2", widthDp = 276, heightDp = 220),
        ),
    )

  @Test
  fun templatesMatchTheSeed() {
    val stale = mutableListOf<String>()
    for ((id, document) in templates) {
      val file = File(catalogDir, "ui-builder/designs/$id.json")
      val expected = json.encodeToString(JsonObject.serializer(), document) + "\n"
      if (update) {
        file.parentFile.mkdirs()
        file.writeText(expected)
      } else if (!file.isFile || file.readText() != expected) {
        stale += file.path
      }
    }
    if (stale.isNotEmpty()) {
      fail(
        "ha-cards templates are out of date with HaCardsSeed: $stale. Regenerate with " +
          "./gradlew :design-server:test --tests '*HaCardsTemplatesTest*' " +
          "-PhaCardsTemplates.update=true"
      )
    }
  }

  @Test
  fun templatesStayInsideThePolicy() {
    val policy =
      Json.parseToJsonElement(File(catalogDir, "ui-builder.policy.json").readText()).jsonObject
    val listed =
      policy["templates"]!!.jsonArray.map { entry ->
        (entry as? JsonPrimitive)?.content ?: entry.jsonObject["path"]!!.jsonPrimitive.content
      }
    assertEquals(templates.keys.map { "ui-builder/designs/$it.json" }, listed)

    val declared = policy["components"]!!.jsonObject.keys + policy["builtins"]!!.jsonObject.keys
    val icons =
      policy["components"]!!
        .jsonObject
        .values
        .flatMap { (it.jsonObject["propertyCapabilities"] as? JsonArray).orEmpty() }
        .map { it.jsonObject }
        .filter { capability ->
          val name = capability["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
          name == "icon" || name.endsWith("Icon")
        }
        .flatMap { (it["allowedValues"] as? JsonArray).orEmpty() }
        .map { it.jsonPrimitive.content }
        .toSet()
    assertTrue(icons.isNotEmpty(), "the policy lists the icon keys")

    for ((id, document) in templates) {
      assertEquals(
        "ha-cards",
        document["catalogPin"]!!.jsonObject["systemId"]!!.jsonPrimitive.content,
      )
      assertEquals(id, document["template"]!!.jsonPrimitive.content)
      for ((nodeId, node) in document["nodes"]!!.jsonObject) {
        val component = node.jsonObject["componentId"]!!.jsonPrimitive.content
        assertTrue(
          component.startsWith("layout/") || component in declared,
          "$id: node $nodeId is `$component`, which ha-cards does not declare",
        )
        val properties = node.jsonObject["properties"]!!.jsonObject
        properties["icon"]?.let {
          val key = it.jsonObject["value"]!!.jsonPrimitive.content
          assertTrue(key in icons, "$id: node $nodeId's icon `$key` is not an allowed key")
        }
      }
    }
  }
}
