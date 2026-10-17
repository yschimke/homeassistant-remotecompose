package ee.schimke.terrazzo.shared

import ee.schimke.ha.model.CardConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.*

class AnonymousTemplateCardTest {
  @Test
  fun repeatedRowsRetainTheirPositionsWithoutSharingPrivateNames() {
    val card =
      CardConfig(
        "entities",
        Json.parseToJsonElement(
            """{
      "type":"entities", "title":"Private house", "entities":[
        "light.private_room", {"entity":"light.private_room","name":"Private label"}, "sensor.private_power"
      ]
    }"""
          )
          .jsonObject,
      )
    val request = anonymousTemplateCard(card)
    assertEquals(
      listOf("light.s0", "light.s1", "sensor.s2"),
      request.getValue("entities").jsonArray.map { it.jsonPrimitive.content },
    )
    assertFalse(request.toString().contains("private", ignoreCase = true))
  }
}
