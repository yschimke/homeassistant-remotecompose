package ee.schimke.ha.rc.cards.shutter

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.shutter.CoverButtonState.Disabled
import ee.schimke.ha.rc.cards.shutter.CoverButtonState.Enabled
import ee.schimke.ha.rc.cards.shutter.CoverButtonState.Hidden
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class CoverControlsTest {

  private fun cover(
    state: String,
    position: Int? = null,
    features: Int? =
      CoverFeature.OPEN or CoverFeature.CLOSE or CoverFeature.SET_POSITION or CoverFeature.STOP,
    extra: Map<String, JsonPrimitive> = emptyMap(),
  ) =
    EntityState(
      entityId = "cover.garage",
      state = state,
      attributes =
        JsonObject(
          buildMap {
            put("device_class", JsonPrimitive("garage"))
            if (position != null) put("current_position", JsonPrimitive(position))
            if (features != null) put("supported_features", JsonPrimitive(features))
            putAll(extra)
          }
        ),
    )

  private fun controls(open: CoverButtonState, stop: CoverButtonState, close: CoverButtonState) =
    CoverControls(open = open, stop = stop, close = close)

  @Test
  fun closedDoorCanOnlyOpen() {
    assertEquals(controls(Enabled, Disabled, Disabled), coverControls(cover("closed", 0)))
    assertEquals(controls(Enabled, Disabled, Disabled), coverControls(cover("closed")))
  }

  @Test
  fun openDoorCanOnlyClose() {
    assertEquals(controls(Disabled, Disabled, Enabled), coverControls(cover("open", 100)))
    assertEquals(controls(Disabled, Disabled, Enabled), coverControls(cover("open")))
  }

  @Test
  fun openingDoorCanStopOrClose() {
    assertEquals(controls(Disabled, Enabled, Enabled), coverControls(cover("opening", 40)))
  }

  @Test
  fun closingDoorCanStopOrOpen() {
    assertEquals(controls(Enabled, Enabled, Disabled), coverControls(cover("closing", 40)))
  }

  @Test
  fun partlyOpenDoorCanOpenOrClose() {
    assertEquals(controls(Enabled, Disabled, Enabled), coverControls(cover("open", 45)))
  }

  @Test
  fun positionWinsOverStateForFullyOpenAndClosed() {
    // HA's isFullyOpen / isFullyClosed read current_position first.
    assertEquals(controls(Enabled, Disabled, Enabled), coverControls(cover("closed", 30)))
  }

  @Test
  fun unsupportedButtonsAreHidden() {
    val openCloseOnly = cover("closed", features = CoverFeature.OPEN or CoverFeature.CLOSE)
    assertEquals(controls(Enabled, Hidden, Disabled), coverControls(openCloseOnly))
    val openOnly = cover("closed", features = CoverFeature.OPEN)
    assertEquals(controls(Enabled, Hidden, Hidden), coverControls(openOnly))
    assertEquals(controls(Hidden, Hidden, Hidden), coverControls(cover("closed", features = 0)))
  }

  @Test
  fun missingSupportedFeaturesAssumesOpenCloseStop() {
    assertEquals(
      controls(Enabled, Disabled, Disabled),
      coverControls(cover("closed", 0, features = null)),
    )
  }

  @Test
  fun unavailableOrMissingCoverDisablesEverything() {
    assertEquals(controls(Disabled, Disabled, Disabled), coverControls(cover("unavailable")))
    assertEquals(controls(Disabled, Disabled, Disabled), coverControls(null))
  }

  @Test
  fun assumedStateKeepsEverySupportedButtonEnabled() {
    val assumed = cover("closed", 0, extra = mapOf("assumed_state" to JsonPrimitive(true)))
    assertEquals(controls(Enabled, Enabled, Enabled), coverControls(assumed))
  }

  @Test
  fun hideStatesFromTheEnhancedShutterConfigHideButtons() {
    // The demo Security dashboard's garage door config.
    val hide =
      CoverHideStates.fromEntry(
        Json.parseToJsonElement(
            """{"entity":"cover.garage",
              "button_up_hide_states":["open","opening","closing"],
              "button_stop_hide_states":["open","closed","partial_open"],
              "button_down_hide_states":["closed","opening","closing"]}"""
          )
          .jsonObject
      )
    assertEquals(controls(Enabled, Hidden, Hidden), coverControls(cover("closed", 0), hide))
    assertEquals(controls(Hidden, Hidden, Enabled), coverControls(cover("open", 100), hide))
    assertEquals(controls(Hidden, Enabled, Hidden), coverControls(cover("opening", 30), hide))
    assertEquals(controls(Enabled, Hidden, Enabled), coverControls(cover("open", 45), hide))
    // Hidden by config, but an unavailable cover still greys what remains.
    assertEquals(controls(Disabled, Disabled, Disabled), coverControls(cover("unavailable"), hide))
  }

  @Test
  fun partialOpenIsTheHideStateOfAPartlyOpenCover() {
    assertEquals("partial_open", coverHideState(cover("open", 45)))
    assertEquals("open", coverHideState(cover("open", 100)))
    assertEquals("closed", coverHideState(cover("closed", 0)))
  }

  @Test
  fun stateLabelSaysThePositionOnlyWhenItAddsSomething() {
    assertEquals("Closed", coverStateLabel(cover("closed", 0)))
    assertEquals("Open", coverStateLabel(cover("open", 100)))
    assertEquals("Open · 45%", coverStateLabel(cover("open", 45)))
    assertEquals("Opening · 30%", coverStateLabel(cover("opening", 30)))
    assertEquals("Closing", coverStateLabel(cover("closing")))
    assertEquals("Closed", coverStateLabel(cover("closed")))
    assertEquals("Unavailable", coverStateLabel(cover("unavailable", 0)))
    assertEquals("Unavailable", coverStateLabel(cover("unknown")))
    assertEquals("Unavailable", coverStateLabel(null))
  }

  @Test
  fun closedFractionFollowsPositionThenState() {
    assertEquals(1f, coverClosedFraction(cover("closed", 0), unknownFraction = 0.5f))
    assertEquals(0f, coverClosedFraction(cover("open", 100), unknownFraction = 0.5f))
    assertEquals(0.75f, coverClosedFraction(cover("opening", 25), unknownFraction = 0.5f))
    assertEquals(0.5f, coverClosedFraction(cover("closing"), unknownFraction = 1f))
    assertEquals(1f, coverClosedFraction(cover("unavailable"), unknownFraction = 1f))
  }

  @Test
  fun garageAndGateDrawAsADoor() {
    assertTrue(isGarageLike(cover("closed")))
    assertFalse(
      isGarageLike(
        EntityState(
          "cover.bedroom",
          "closed",
          JsonObject(mapOf("device_class" to JsonPrimitive("shutter"))),
        )
      )
    )
    assertFalse(isGarageLike(EntityState("cover.bedroom", "closed")))
  }

  @Test
  fun bothConvertersReEncodeWhenTheCoverChanges() {
    val enhanced =
      CardConfig(
        "custom:enhanced-shutter-card",
        Json.parseToJsonElement(
            """{"type":"custom:enhanced-shutter-card","entities":[{"entity":"cover.garage"}]}"""
          )
          .jsonObject,
      )
    val garage =
      CardConfig(
        "custom:garage-shutter-card",
        Json.parseToJsonElement("""{"type":"custom:garage-shutter-card","entity":"cover.garage"}""")
          .jsonObject,
      )
    val closed = HaSnapshot(states = mapOf("cover.garage" to cover("closed", 0)))
    val opening = HaSnapshot(states = mapOf("cover.garage" to cover("opening", 10)))
    for ((converter, card) in
      listOf(EnhancedShutterCardConverter() to enhanced, GarageShutterCardConverter() to garage)) {
      val before = converter.dataSignature(card, closed)
      val after = converter.dataSignature(card, opening)
      assertTrue(!before.isNullOrEmpty(), "${card.type} has a data signature")
      assertNotEquals(before, after, "${card.type} re-encodes on a state change")
    }
  }
}
