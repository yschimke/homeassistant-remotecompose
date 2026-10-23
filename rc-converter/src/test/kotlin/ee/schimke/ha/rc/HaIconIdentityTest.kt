package ee.schimke.ha.rc

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The icon exception of `docs/architecture/card-information-hierarchy.md`: which icons may stand in
 * for the name on a 1×1 launcher widget.
 */
class HaIconIdentityTest {

  private fun card(entity: String?, icon: String? = null) =
    CardConfig(
      type = "button",
      raw =
        buildJsonObject {
          put("type", "button")
          entity?.let { put("entity", it) }
          icon?.let { put("icon", it) }
        },
    )

  private fun entity(id: String, deviceClass: String? = null) =
    EntityState(
      id,
      "on",
      buildJsonObject { deviceClass?.let { put("device_class", it) } },
    )

  @Test
  fun theGuidelinesEvocativeDomainsMayDropTheName() {
    for (domain in
      listOf(
        "lock",
        "climate",
        "alarm_control_panel",
        "vacuum",
        "media_player",
        "camera",
        "water_heater",
      )) {
      assertTrue(HaIconIdentity.isEvocative(domain, null, null), domain)
      assertFalse(HaIconIdentity.chipKeepsName(card("$domain.x"), entity("$domain.x")), domain)
    }
  }

  @Test
  fun theGuidelinesGenericDomainsKeepTheName() {
    for (domain in
      listOf(
        "light",
        "switch",
        "input_boolean",
        "fan",
        "sensor",
        "binary_sensor",
        "script",
        "scene",
        "button",
      )) {
      assertFalse(HaIconIdentity.isEvocative(domain, null, null), domain)
      assertTrue(HaIconIdentity.chipKeepsName(card("$domain.x"), entity("$domain.x")), domain)
    }
  }

  @Test
  fun aCoverIsEvocativeOnlyAsAGarageGateOrDoor() {
    for (deviceClass in listOf("garage", "gate", "door")) {
      assertTrue(HaIconIdentity.isEvocative(card("cover.x"), entity("cover.x", deviceClass)))
    }
    for (deviceClass in listOf(null, "blind", "shade", "window", "curtain")) {
      assertFalse(
        HaIconIdentity.isEvocative(card("cover.x"), entity("cover.x", deviceClass)),
        "$deviceClass",
      )
    }
  }

  @Test
  fun theDeviceClassOnlyMattersForACover() {
    // A garage-door binary sensor is still a generic sensor icon.
    assertFalse(HaIconIdentity.isEvocative("binary_sensor", "garage_door", null))
    assertFalse(HaIconIdentity.isEvocative("sensor", "door", null))
  }

  @Test
  fun aConfiguredIconIsEvocativeWhateverTheDomain() {
    assertTrue(HaIconIdentity.isEvocative(card("light.kitchen", "mdi:ceiling-light"), null))
    assertTrue(HaIconIdentity.isEvocative(card("sensor.t", "mdi:sofa"), entity("sensor.t")))
    assertFalse(HaIconIdentity.chipKeepsName(card(null, "mdi:rocket"), null))
  }

  @Test
  fun aBlankOrNullIconIsNotConfigured() {
    assertFalse(HaIconIdentity.isEvocative(card("light.kitchen", ""), null))
    assertFalse(HaIconIdentity.isEvocative(card("light.kitchen", "  "), null))
    val nullIcon =
      CardConfig(
        "button",
        JsonObject(
          mapOf("entity" to kotlinx.serialization.json.JsonPrimitive("light.k"), "icon" to JsonNull)
        ),
      )
    assertFalse(HaIconIdentity.isEvocative(nullIcon, null))
  }

  @Test
  fun anUnknownOrMissingEntityKeepsTheName() {
    assertTrue(HaIconIdentity.chipKeepsName(card(null), null))
    assertTrue(HaIconIdentity.chipKeepsName(card("weather.home"), null))
    assertTrue(HaIconIdentity.chipKeepsName(card("not-an-entity-id"), null))
  }

  @Test
  fun theDomainComesFromTheEntityWhenTheCardHasNone() {
    assertFalse(HaIconIdentity.chipKeepsName(card(null), entity("lock.front_door")))
  }
}
