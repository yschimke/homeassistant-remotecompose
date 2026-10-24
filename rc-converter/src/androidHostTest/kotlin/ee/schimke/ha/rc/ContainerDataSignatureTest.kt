package ee.schimke.ha.rc

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.defaultRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A container's document bakes what its children bake, so the host's signature lookup
 * ([cardDataSignature]) folds in every child: a grid holding a re-encode tile re-encodes when the
 * tile's data moves, and a grid of binding cards stays on the binding path. (The Climate demo's
 * thermostat tiles sit inside `grid` cards.)
 */
class ContainerDataSignatureTest {

  private val registry = defaultRegistry()

  private fun climate(target: Double = 22.0, action: String = "heating") =
    EntityState(
      "climate.kids_room",
      "heat",
      JsonObject(
        mapOf(
          "hvac_modes" to JsonArray(listOf(JsonPrimitive("heat"), JsonPrimitive("off"))),
          "current_temperature" to JsonPrimitive(21.8),
          "temperature" to JsonPrimitive(target),
          "hvac_action" to JsonPrimitive(action),
          "min_temp" to JsonPrimitive(10),
          "max_temp" to JsonPrimitive(32),
        )
      ),
    )

  private fun snapshot(climate: EntityState = climate()) =
    HaSnapshot(
      states =
        listOf(climate, EntityState("light.kitchen", "on"), EntityState("switch.fan", "off"))
          .associateBy { it.entityId }
    )

  private fun card(type: String, block: JsonObjectBuilder.() -> Unit) =
    CardConfig(
      type = type,
      raw =
        buildJsonObject {
          put("type", type)
          block()
        },
    )

  private fun JsonObjectBuilder.climateTile() {
    put("type", "tile")
    put("entity", "climate.kids_room")
    putJsonArray("features") {
      addJsonObject { put("type", "target-temperature") }
      addJsonObject { put("type", "climate-hvac-modes") }
    }
  }

  private fun JsonObjectBuilder.plainTile(entity: String) {
    put("type", "tile")
    put("entity", entity)
  }

  private val climateGrid =
    card("grid") {
      putJsonArray("cards") {
        addJsonObject { plainTile("light.kitchen") }
        addJsonObject { climateTile() }
      }
    }

  @Test
  fun aGridWithAClimateTileReEncodesOnItsData() {
    val base = assertNotNull(registry.cardDataSignature(climateGrid, snapshot()))
    assertNotEquals(base, registry.cardDataSignature(climateGrid, snapshot(climate(target = 22.5))))
    assertNotEquals(
      base,
      registry.cardDataSignature(climateGrid, snapshot(climate(action = "idle"))),
    )
    assertEquals(base, registry.cardDataSignature(climateGrid, snapshot()), "deterministic")
  }

  @Test
  fun aGridOfPlainTilesStaysOnTheBindingPath() {
    val grid =
      card("grid") {
        putJsonArray("cards") {
          addJsonObject { plainTile("light.kitchen") }
          addJsonObject { plainTile("switch.fan") }
        }
      }
    assertNull(registry.cardDataSignature(grid, snapshot()))
  }

  @Test
  fun stacksAndWrappersFoldInNestedChildren() {
    val nested =
      card("vertical-stack") {
        putJsonArray("cards") {
          addJsonObject {
            put("type", "horizontal-stack")
            putJsonArray("cards") { addJsonObject { plainTile("light.kitchen") } }
          }
          addJsonObject {
            put("type", "conditional")
            putJsonArray("conditions") {}
            putJsonObject("card") { climateTile() }
          }
        }
      }
    val base = assertNotNull(registry.cardDataSignature(nested, snapshot()))
    assertNotEquals(base, registry.cardDataSignature(nested, snapshot(climate(target = 23.0))))

    val hidden =
      card("conditional") {
        putJsonArray("conditions") {
          addJsonObject {
            put("entity", "switch.fan")
            put("state", "on")
          }
        }
        putJsonObject("card") { climateTile() }
      }
    assertNull(registry.cardDataSignature(hidden, snapshot()), "a hidden card bakes nothing")
  }
}
