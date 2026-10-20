package ee.schimke.ha.rc.cards

import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The tile's generation properties: what picks its card design template. */
class TileDesignPropertiesTest {

  private val tile = TileCardConverter()

  private fun card(entity: String, vararg extra: Pair<String, String>) =
    CardConfig(
      type = "tile",
      raw =
        buildJsonObject {
          put("type", "tile")
          put("entity", entity)
          for ((k, v) in extra) put(k, v)
        },
    )

  private fun snapshotOf(vararg states: EntityState) =
    HaSnapshot(states = states.associateBy { it.entityId })

  private fun state(id: String, value: String = "on", vararg attrs: Pair<String, String>) =
    EntityState(id, value, JsonObject(attrs.associate { (k, v) -> k to JsonPrimitive(v) }))

  @Test
  fun valuesNeverChangeTheProperties() {
    val snapshot =
      snapshotOf(
        state("light.kitchen", "on", "friendly_name" to "Kitchen"),
        state("light.hall", "off"),
      )
    val kitchen = tile.designProperties(card("light.kitchen"), snapshot)
    assertEquals("domain=light", kitchen?.shape)
    assertEquals(kitchen, tile.designProperties(card("light.hall", "name" to "Hall"), snapshot))
  }

  @Test
  fun propertiesCarryWhatChangesTheTilesLook() {
    val snapshot =
      snapshotOf(
        state("binary_sensor.door", "off", "device_class" to "door"),
        state("sensor.temp", "21", "device_class" to "temperature", "icon" to "MDI:Thermometer"),
        state("switch.fan", "on", "device_class" to "outlet"),
      )
    assertEquals(
      "device_class=door;domain=binary_sensor",
      tile.designProperties(card("binary_sensor.door"), snapshot)?.shape,
    )
    assertEquals(
      "device_class=temperature;domain=sensor;icon=mdi:thermometer",
      tile.designProperties(card("sensor.temp"), snapshot)?.shape,
    )
    // A switch's device_class doesn't change its tile, so it isn't a property.
    assertEquals("domain=switch", tile.designProperties(card("switch.fan"), snapshot)?.shape)
  }

  @Test
  fun unmodelledTilesHaveNoProperties() {
    val snapshot = snapshotOf(state("light.kitchen"), state("climate.living", "heat"))
    assertNull(tile.designProperties(card("light.kitchen", "color" to "red"), snapshot))
    assertNull(tile.designProperties(card("climate.living"), snapshot))
    // Not loaded yet: the converter renders the missing entity.
    assertNull(tile.designProperties(card("light.unknown"), snapshot))
  }

  @Test
  fun configIconIsAProperty() {
    val snapshot = snapshotOf(state("light.kitchen"))
    assertEquals(
      "card_icon=mdi:lamp;domain=light",
      tile.designProperties(card("light.kitchen", "icon" to "mdi:lamp"), snapshot)?.shape,
    )
    val input =
      assertNotNull(tile.designTemplate(DesignProperties.parse("card_icon=mdi:lamp;domain=light")))
    assertEquals("mdi:lamp", input.card.raw["icon"]?.jsonPrimitive?.content)
  }

  @Test
  fun templateMapsPropertiesBackOntoASlotEntity() {
    val input =
      assertNotNull(
        tile.designTemplate(
          DesignProperties.build {
            string("domain", "sensor")
            string("device_class", "temperature")
            string("icon", "mdi:thermometer")
          }
        )
      )
    assertEquals("sensor.s0", input.card.raw["entity"]?.jsonPrimitive?.content)
    val entity = assertNotNull(input.snapshot.states["sensor.s0"])
    assertEquals("temperature", entity.attributes["device_class"]?.jsonPrimitive?.content)
    assertEquals("mdi:thermometer", entity.attributes["icon"]?.jsonPrimitive?.content)
    // Round trip: the template's own properties are the ones it was built from.
    assertEquals(
      "device_class=temperature;domain=sensor;icon=mdi:thermometer",
      tile.designProperties(input.card, input.snapshot)?.shape,
    )
  }

  @Test
  fun aMeteredSensorCarriesItsUnit_neverItsValue() {
    val reading = arrayOf("device_class" to "temperature", "unit_of_measurement" to "°F")
    val warm = snapshotOf(state("sensor.porch", "71.6", *reading))
    val offline = snapshotOf(state("sensor.porch", "unavailable", *reading))
    val shape = "device_class=temperature;domain=sensor;unit=°F"
    assertEquals(shape, tile.designProperties(card("sensor.porch"), warm)?.shape)
    // The glyph is decided from attributes, so an unavailable sensor keys the same template.
    assertEquals(shape, tile.designProperties(card("sensor.porch"), offline)?.shape)
    // An unclassified reading, or an energy total, draws no glyph: its unit is no property.
    val plain = snapshotOf(state("sensor.count", "3", "unit_of_measurement" to "items"))
    assertEquals("domain=sensor", tile.designProperties(card("sensor.count"), plain)?.shape)
    val energy =
      snapshotOf(
        state("sensor.meter", "12", "device_class" to "energy", "unit_of_measurement" to "kWh")
      )
    assertEquals(
      "device_class=energy;domain=sensor",
      tile.designProperties(card("sensor.meter"), energy)?.shape,
    )
  }

  @Test
  fun theTemplateSlotCarriesTheUnit() {
    val properties = DesignProperties.parse("device_class=temperature;domain=sensor;unit=°F")
    val input = assertNotNull(tile.designTemplate(properties))
    val entity = assertNotNull(input.snapshot.states["sensor.s0"])
    assertEquals("°F", entity.attributes["unit_of_measurement"]?.jsonPrimitive?.content)
    assertEquals(properties, tile.designProperties(input.card, input.snapshot))
  }
}
