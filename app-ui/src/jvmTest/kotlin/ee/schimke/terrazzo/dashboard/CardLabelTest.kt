package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardKey
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CardLabelTest {
  @Test
  fun aCommentNamesTheCardAndWhereItIs() {
    val camera =
      CardConfig("picture-entity", buildJsonObject { put("entity", "camera.front_door") })
    assertEquals(
      "camera.front_door (view security, section 2, card 3)",
      cardLabel(CardKey(null, "security", 1, 2, "picture-entity"), camera),
    )
    assertEquals(
      "camera.front_door (view 2, card 1)",
      cardLabel(CardKey(null, "view-1", null, 0, "picture-entity"), camera),
    )
    val titled = CardConfig("entities", buildJsonObject { put("title", "Locks") })
    assertEquals("Locks (card 1)", cardLabel(CardKey(null, null, null, 0, "entities"), titled))
  }

  @Test
  fun anEntityCardIsNamedAsHomeAssistantNamesIt() {
    val tile =
      CardConfig("tile", buildJsonObject { put("entity", "sensor.living_room_temperature") })
    val snapshot =
      HaSnapshot(
        states =
          mapOf(
            "sensor.living_room_temperature" to
              EntityState(
                "sensor.living_room_temperature",
                "21.4",
                buildJsonObject { put("friendly_name", "Living room temperature") },
              ),
          ),
      )
    assertEquals(
      "Living room temperature (view 1, section 2, card 2)",
      cardLabel(CardKey(null, "view-0", 1, 1, "tile"), tile, snapshot),
    )
    // A card's own name still wins.
    val named =
      CardConfig(
        "tile",
        buildJsonObject {
          put("entity", "sensor.living_room_temperature")
          put("name", "Lounge")
        },
      )
    assertEquals(
      "Lounge (card 1)",
      cardLabel(CardKey(null, null, null, 0, "tile"), named, snapshot),
    )
  }
}
