package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardKey
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
    val titled = CardConfig("entities", buildJsonObject { put("title", "Locks") })
    assertEquals("Locks (card 1)", cardLabel(CardKey(null, null, null, 0, "entities"), titled))
  }
}
