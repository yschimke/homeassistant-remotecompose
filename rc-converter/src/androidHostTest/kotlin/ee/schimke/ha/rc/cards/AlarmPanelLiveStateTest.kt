package ee.schimke.ha.rc.cards

import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.captureCardDocument
import ee.schimke.ha.rc.cardDataSignature
import ee.schimke.ha.rc.components.HaAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The alarm panel's button row follows its entity as Home Assistant's card does: the configured ARM
 * states while `disarmed`, DISARM in every other state. The row is baked, so the card must
 * re-encode (its `dataSignature`) and, on the design path, re-key (`disarm_row`) when it changes.
 */
@Suppress("RestrictedApi")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlarmPanelLiveStateTest {
  private val registry = defaultRegistry()
  private val converter = AlarmPanelCardConverter()
  private val id = "alarm_control_panel.home"

  private val card =
    CardConfig(
      type = "alarm-panel",
      raw =
        buildJsonObject {
          put("type", "alarm-panel")
          put("entity", id)
          putJsonArray("states") {
            add("arm_away")
            add("arm_home")
          }
        },
    )

  private fun snapshot(state: String, vararg attrs: Pair<String, JsonElement>) =
    HaSnapshot(
      states = mapOf(id to EntityState(id, state, JsonObject(mapOf(*attrs) + friendlyName)))
    )

  private val friendlyName = "friendly_name" to JsonPrimitive("Home alarm")

  private fun documentText(snapshot: HaSnapshot): String = runBlocking {
    val doc =
      captureCardDocument(
        context = org.robolectric.RuntimeEnvironment.getApplication(),
        widthPx = 720,
        heightPx = 1000,
        registry = registry,
        card = card,
        snapshot = snapshot,
        forceRefresh = true,
      )
    String(doc.bytes, Charsets.UTF_8)
  }

  @Test
  fun anArmedPanelOffersDisarmInsteadOfItsArmButtons() {
    val armed = documentText(snapshot("armed_away"))
    assertTrue("DISARM" in armed, "an armed panel must offer DISARM")
    assertFalse("ARM AWAY" in armed, "an armed panel offers no ARM button")
    assertFalse("ARM HOME" in armed)

    val disarmed = documentText(snapshot("disarmed"))
    assertTrue("ARM AWAY" in disarmed)
    assertTrue("ARM HOME" in disarmed)
    assertFalse("DISARM" in disarmed, "a disarmed panel offers no DISARM button")
  }

  @Test
  fun everyStateButDisarmedOffersDisarmAsInHomeAssistant() {
    val slot = CardDesignSlots.slotId("alarm_control_panel", 0)
    fun offered(state: String, service: String) =
      converter.designAction(card, snapshot(state), 0, HaAction.AlarmIntent(slot, service, 0)) !=
        null
    for (state in
      listOf(
        "armed_home",
        "armed_away",
        "armed_night",
        "armed_vacation",
        "armed_custom_bypass",
        "pending",
        "arming",
        "disarming",
        "triggered",
        "unavailable",
        "unknown",
      )) {
      assertTrue(offered(state, "disarm"), "$state offers DISARM")
      assertFalse(offered(state, "arm_away"), "$state offers no ARM button")
    }
    assertTrue(offered("disarmed", "arm_away"))
    assertTrue(offered("disarmed", "arm_home"))
    assertFalse(offered("disarmed", "disarm"))
  }

  @Test
  fun arrivingAtOrLeavingDisarmedReEncodesTheCard() {
    val disarmed = assertNotNull(registry.cardDataSignature(card, snapshot("disarmed")))
    val armed = assertNotNull(registry.cardDataSignature(card, snapshot("armed_away")))
    assertNotEquals(disarmed, armed)
    // A re-encode card takes no binding push, so a change between armed states re-encodes too.
    assertNotEquals(armed, registry.cardDataSignature(card, snapshot("armed_home")))
    assertEquals(disarmed, registry.cardDataSignature(card, snapshot("disarmed")))
  }

  @Test
  fun theDesignKeyFollowsTheButtonRow() {
    val disarmed = assertNotNull(converter.designProperties(card, snapshot("disarmed")))
    val armedAway = assertNotNull(converter.designProperties(card, snapshot("armed_away")))
    val triggered = assertNotNull(converter.designProperties(card, snapshot("triggered")))
    assertNotEquals(disarmed, armedAway, "armed and disarmed draw different button rows")
    // The status itself is `<slot>.state_int`: armed states share one template.
    assertEquals(armedAway, triggered)
    assertNull(disarmed["disarm_row"], "a disarmed panel keeps the key it always had")

    // Each template is captured from a placeholder entity in a state that draws its row.
    val armedTemplate = assertNotNull(converter.designTemplate(armedAway))
    assertNotEquals("disarmed", armedTemplate.snapshot.states.values.single().state)
    val disarmedTemplate = assertNotNull(converter.designTemplate(disarmed))
    assertEquals("disarmed", disarmedTemplate.snapshot.states.values.single().state)
  }

  @Test
  fun aTemplateDisarmTapRunsOnlyWhileTheEntityIsArmed() {
    val slot = CardDesignSlots.slotId("alarm_control_panel", 0)
    val armed = snapshot("armed_away", "code_format" to JsonPrimitive("number"))
    assertEquals(
      HaAction.AlarmIntent(id, "disarm", null),
      converter.designAction(card, armed, 0, HaAction.AlarmIntent(slot, "disarm", 0)),
    )
    assertNull(converter.designAction(card, armed, 0, HaAction.AlarmIntent(slot, "arm_home", 0)))
    val disarmed = snapshot("disarmed")
    assertNull(converter.designAction(card, disarmed, 0, HaAction.AlarmIntent(slot, "disarm", 0)))
    assertEquals(
      HaAction.AlarmIntent(id, "arm_home", null),
      converter.designAction(card, disarmed, 0, HaAction.AlarmIntent(slot, "arm_home", 0)),
    )
  }

  @Test
  fun disarmingIgnoresCodeArmRequired() {
    val slot = CardDesignSlots.slotId("alarm_control_panel", 0)
    val tap = HaAction.AlarmIntent(slot, "disarm", 0)
    // code_arm_required only covers arming: a panel with a code still needs it to disarm.
    val withCode =
      snapshot(
        "armed_home",
        "code_arm_required" to JsonPrimitive(false),
        "code_format" to JsonPrimitive("number"),
      )
    assertEquals(
      HaAction.AlarmIntent(id, "disarm", null),
      converter.designAction(card, withCode, 0, tap),
    )
    // A panel without a code (code_format: null) disarms at once.
    val noCode = snapshot("armed_home", "code_format" to JsonNull)
    assertEquals(
      HaAction.AlarmIntent(id, "disarm", 0),
      converter.designAction(card, noCode, 0, tap),
    )
  }
}
