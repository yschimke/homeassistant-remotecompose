package ee.schimke.ha.rc.cards

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.EntityRowMinHeightDp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * HA's special entities-card rows (`section`, `divider`, `weblink`, …: a `type` and no `entity`)
 * are not entity rows: they must not render as a "—" row with a switch.
 */
class EntitiesCardConverterTest {

  private fun card(entities: JsonArrayBuilder.() -> Unit) =
    CardConfig(
      type = "entities",
      raw =
        buildJsonObject {
          put("type", "entities")
          putJsonArray("entities", entities)
        },
    )

  private val mixed = card {
    add("light.lamp")
    addJsonObject { put("type", "divider") }
    addJsonObject {
      put("type", "section")
      put("label", "Sensors")
    }
    add("binary_sensor.door")
    addJsonObject {
      put("type", "weblink")
      put("name", "Docs")
      put("url", "https://www.home-assistant.io")
    }
    addJsonObject {
      put("type", "call-service")
      put("name", "Run")
      put("service", "script.turn_on")
    }
    addJsonObject {
      put("type", "conditional")
      putJsonArray("conditions") {}
      put("row", buildJsonObject { put("entity", "switch.hidden") })
    }
    addJsonObject {
      put("entity", "switch.coffee")
      put("name", "Coffee")
    }
    addJsonObject {
      put("type", "attribute")
      put("entity", "light.lamp")
      put("attribute", "brightness")
    }
    addJsonObject {
      put("type", "simple-entity")
      put("entity", "switch.coffee")
    }
  }

  @Test
  fun specialRowsWithoutAnEntityAreNotEntityRows() {
    val rows = EntitiesCardConverter.entityRows(mixed)
    assertEquals(
      listOf("light.lamp", "binary_sensor.door", "switch.coffee", "light.lamp", "switch.coffee"),
      rows.map { it.entityId },
    )
  }

  @Test
  fun onlySwitchableEntityRowsToggle() {
    val toggles = EntitiesCardConverter.entityRows(mixed).map { it.entityId to it.toggle }
    assertEquals(
      listOf(
        "light.lamp" to true,
        "binary_sensor.door" to false,
        "switch.coffee" to true,
        // `attribute` and `simple-entity` rows show a value, never a switch.
        "light.lamp" to false,
        "switch.coffee" to false,
      ),
      toggles,
    )
  }

  @Test
  fun aRowWithNoEntityNeverToggles() {
    val rows = EntitiesCardConverter.entityRows(card { addJsonObject { put("name", "Nothing") } })
    assertEquals(listOf<String?>(null), rows.map { it.entityId })
    assertEquals(listOf(false), rows.map { it.toggle })
  }

  @Test
  fun naturalHeightCountsOnlyEntityRows() {
    val withDividers = card {
      add("light.lamp")
      addJsonObject { put("type", "divider") }
      add("switch.coffee")
      addJsonObject {
        put("type", "section")
        put("label", "More")
      }
    }
    val plain = card {
      add("light.lamp")
      add("switch.coffee")
    }
    val converter = EntitiesCardConverter()
    val snapshot = HaSnapshot()
    assertEquals(16 + 2 * EntityRowMinHeightDp, converter.naturalHeightDp(plain, snapshot))
    assertEquals(
      converter.naturalHeightDp(plain, snapshot),
      converter.naturalHeightDp(withDividers, snapshot),
    )
  }
}
