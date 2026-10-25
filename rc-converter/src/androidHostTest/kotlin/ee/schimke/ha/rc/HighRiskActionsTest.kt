package ee.schimke.ha.rc

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.HaAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Which actions hold to the phone's lock mode ([HighRiskActions]), and a card's own action. */
class HighRiskActionsTest {
  private fun cover(id: String, deviceClass: String?) =
    id to
      EntityState(
        entityId = id,
        state = "closed",
        attributes =
          JsonObject(deviceClass?.let { mapOf("device_class" to JsonPrimitive(it)) }.orEmpty()),
      )

  private val snapshot =
    HaSnapshot(
      states =
        mapOf(
          cover("cover.garage", "garage"),
          cover("cover.gate", "gate"),
          cover("cover.blinds", "blind"),
          cover("cover.unknown", null),
        )
    )

  private fun risky(action: HaAction) = HighRiskActions.isHighRisk(action, snapshot)

  @Test
  fun locksAndAlarmPanelsAreAlwaysHighRisk() {
    assertTrue(risky(HaAction.CallService("lock", "unlock", "lock.front_door")))
    assertTrue(risky(HaAction.Toggle("lock.front_door")))
    // A generic service still names the lock.
    assertTrue(risky(HaAction.CallService("homeassistant", "toggle", "lock.front_door")))
    assertTrue(risky(HaAction.AlarmIntent("alarm_control_panel.house", "disarm", 0)))
    assertTrue(risky(HaAction.CallService("alarm_control_panel", "alarm_disarm")))
  }

  @Test
  fun aCoverIsHighRiskWhenItOpensAWayInOrItsClassIsUnknown() {
    assertTrue(risky(HaAction.CallService("cover", "open_cover", "cover.garage")))
    assertTrue(risky(HaAction.Toggle("cover.gate")))
    assertTrue(risky(HaAction.Toggle("cover.unknown")))
    assertFalse(risky(HaAction.CallService("cover", "open_cover", "cover.blinds")))
  }

  @Test
  fun everythingElseRunsUnasked() {
    assertFalse(risky(HaAction.Toggle("light.kitchen")))
    assertFalse(risky(HaAction.CallService("script", "turn_on", "script.movie_night")))
    assertFalse(risky(HaAction.MoreInfo("lock.front_door")))
    assertFalse(risky(HaAction.Navigate("/lovelace/doors")))
    assertFalse(risky(HaAction.None))
  }

  @Test
  fun aCardsPrimaryActionIsItsTapActionElseTheDefaultForItsEntity() {
    assertEquals(
      HaAction.Toggle("light.kitchen"),
      CardDesignTemplates.primaryAction(
        CardConfig("tile", buildJsonObject { put("entity", "light.kitchen") })
      ),
    )
    // A lock defaults to more-info, never to actuating it.
    assertEquals(
      HaAction.MoreInfo("lock.front_door"),
      CardDesignTemplates.primaryAction(
        CardConfig("tile", buildJsonObject { put("entity", "lock.front_door") })
      ),
    )
    assertEquals(
      HaAction.None,
      CardDesignTemplates.primaryAction(
        CardConfig(
          "tile",
          buildJsonObject {
            put("entity", "light.kitchen")
            put("tap_action", buildJsonObject { put("action", "none") })
          },
        )
      ),
    )
  }
}
