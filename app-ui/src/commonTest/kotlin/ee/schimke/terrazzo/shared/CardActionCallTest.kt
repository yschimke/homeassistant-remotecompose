package ee.schimke.terrazzo.shared

import kotlin.test.*
import kotlinx.serialization.json.*

class CardActionCallTest {
  @Test
  fun toggleSendsHomeAssistantToggleLikeAndroid() {
    for (type in listOf("Toggle", "ee.schimke.ha.rc.components.HaAction.Toggle")) {
      for (entity in listOf("light.kitchen", "switch.coffee", "input_boolean.guest", "fan.attic")) {
        assertEquals(
          CardActionCall("homeassistant", "toggle", entity),
          cardActionCall("""{"type":"$type","entityId":"$entity"}"""),
        )
      }
    }
  }

  @Test
  fun callServiceKeepsDeclaredDomainServiceAndData() {
    assertEquals(
      CardActionCall(
        "light",
        "turn_on",
        "light.kitchen",
        buildJsonObject { put("brightness", 128) },
      ),
      cardActionCall(
        """{"type":"CallService","domain":"light","service":"turn_on","entityId":"light.kitchen","serviceData":{"brightness":128}}"""
      ),
    )
  }

  @Test
  fun noneIsNoCallAndUnknownActionsFail() {
    assertNull(cardActionCall("""{"type":"None"}"""))
    assertFailsWith<IllegalStateException> { cardActionCall("""{"type":"Navigate","path":"/x"}""") }
    assertFailsWith<IllegalArgumentException> { cardActionCall("""{"type":"Toggle"}""") }
  }
}
