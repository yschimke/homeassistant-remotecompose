package ee.schimke.terrazzo.shared

import ee.schimke.ha.model.CardConfig
import kotlin.test.*
import kotlinx.serialization.json.*

class CardServiceActionTest {
  private fun action(raw: String) =
    cardServiceAction(Json.decodeFromString(CardConfig.serializer(), raw))

  @Test
  fun declaredServiceAndTargetArePreserved() {
    val request =
      assertNotNull(
        action(
          """{"type":"button","tap_action":{"action":"perform-action","perform_action":"meshcore.send_ui_message","data":{"entry_id":"radio-one"}}}"""
        )
      )
    assertEquals("meshcore", request.domain)
    assertEquals("send_ui_message", request.service)
    assertEquals(JsonPrimitive("radio-one"), request.data["entry_id"])
    val script = assertNotNull(action("""{"type":"button","entity":"script.check_radio"}"""))
    assertEquals("turn_on", script.service)
    assertEquals(JsonPrimitive("script.check_radio"), script.data["entity_id"])
  }

  @Test
  fun unsupportedActionsDoNotTurnIntoServiceCalls() {
    assertNull(action("""{"type":"button","entity":"script.x","tap_action":{"action":"none"}}"""))
    assertNull(
      action(
        """{"type":"button","tap_action":{"action":"call-service","service":"javascript:alert(1)"}}"""
      )
    )
    assertNull(
      action(
        """{"type":"button","tap_action":{"action":"call-service","service":"light.turn_on","target":{"area_id":"room"}}}"""
      )
    )
  }

  @Test
  fun nativeTapConfigurationOverridesGenericTemplateDefaults() {
    val none =
      Json.decodeFromString(
        CardConfig.serializer(),
        """{"type":"tile","entity":"light.room","tap_action":{"action":"none"}}""",
      )
    val info =
      Json.decodeFromString(
        CardConfig.serializer(),
        """{"type":"tile","entity":"light.room","tap_action":{"action":"more-info"}}""",
      )
    val toggle = """{"type":"Toggle","entityId":"light.s0"}"""
    val more = """{"type":"MoreInfo","entityId":"light.s0"}"""
    assertNull(demoToggleEntity(none, toggle))
    assertNull(demoMoreInfoEntity(none, more))
    assertNull(demoToggleEntity(info, toggle))
    assertEquals("light.room", demoMoreInfoEntity(info, toggle))
  }
}
