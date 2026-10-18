package ee.schimke.terrazzo.shared

import ee.schimke.ha.model.CardConfig
import kotlin.test.*
import kotlinx.serialization.json.*

class DemoActionTest {
  private val card =
    CardConfig(
      "entities",
      raw =
        Json.parseToJsonElement(
            """{"entities":["sensor.demo_temperature","light.demo_lamp","switch.demo_coffee"]}"""
          )
          .jsonObject,
    )

  @Test
  fun templateToggleResolvesOnlyCardLocalControllableSlots() {
    assertEquals(
      "light.demo_lamp",
      demoToggleEntity(card, """{"type":"Toggle","entityId":"light.s1"}"""),
    )
    assertEquals(
      "switch.demo_coffee",
      demoToggleEntity(
        card,
        """{"type":"ee.schimke.ha.rc.components.HaAction.Toggle","entityId":"switch.s2"}""",
      ),
    )
    for (entity in
      listOf("sensor.s0", "light.s0", "light.s99", "light.private", "light.demo_lamp")) {
      assertNull(demoToggleEntity(card, """{"type":"Toggle","entityId":"$entity"}"""))
    }
  }

  @Test
  fun administrativeAndMalformedTemplateActionsAreIgnored() {
    for (payload in
      listOf(
        null,
        "null",
        "[]",
        "{broken",
        "{}",
        """{"type":"CallService","domain":"homeassistant","service":"restart","entityId":"light.s1"}""",
        """{"type":[],"entityId":{}}""",
      )) {
      assertNull(demoToggleEntity(card, payload))
    }
  }
}
