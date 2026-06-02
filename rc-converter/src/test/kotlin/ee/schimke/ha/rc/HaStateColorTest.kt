package ee.schimke.ha.rc

import androidx.compose.ui.graphics.Color
import ee.schimke.ha.model.EntityState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class HaStateColorTest {

  private val grayOff = Color(0xFFB0B0B0)
  private val grayInactive = Color(0xFF757575)

  private fun state(entityId: String, state: String, deviceClass: String? = null) =
    EntityState(
      entityId = entityId,
      state = state,
      attributes = buildJsonObject { if (deviceClass != null) put("device_class", deviceClass) },
    )

  // --- resolve -------------------------------------------------------------

  @Test
  fun resolveNullEntityIsInactiveGray() {
    assertEquals(grayInactive, HaStateColor.resolve(null))
  }

  @Test
  fun resolveSensorUsesDeviceClassColor() {
    assertEquals(Color(0xFF2196F3), HaStateColor.resolve(state("sensor.t", "21.0", "temperature")))
    assertEquals(Color(0xFFFFA000), HaStateColor.resolve(state("sensor.p", "120", "power")))
  }

  @Test
  fun resolveSensorWithoutKnownDeviceClassFallsBack() {
    assertEquals(Color(0xFF546E7A), HaStateColor.resolve(state("sensor.x", "5")))
    assertEquals(Color(0xFF546E7A), HaStateColor.resolve(state("sensor.x", "5", "nonsense")))
  }

  @Test
  fun resolveBinarySensorOnUsesDeviceClassButOffIsGray() {
    assertEquals(Color(0xFFE65100), HaStateColor.resolve(state("binary_sensor.m", "on", "motion")))
    assertEquals(grayOff, HaStateColor.resolve(state("binary_sensor.m", "off", "motion")))
  }

  @Test
  fun resolveTrackerIsGreenWhenHomeGrayWhenAway() {
    assertEquals(Color(0xFF4CAF50), HaStateColor.resolve(state("person.a", "home")))
    assertEquals(grayOff, HaStateColor.resolve(state("device_tracker.a", "not_home")))
  }

  @Test
  fun resolveToggleableUsesActiveColorOnlyWhenOn() {
    // Light on → its active accent; off → gray, regardless of accent.
    assertEquals(Color(0xFFFFBE3E), HaStateColor.resolve(state("light.k", "on")))
    assertEquals(grayOff, HaStateColor.resolve(state("light.k", "off")))
  }

  // --- activeFor (state-independent) --------------------------------------

  @Test
  fun activeForPerDomainAccents() {
    assertEquals(Color(0xFFFFBE3E), HaStateColor.activeFor(state("light.k", "off")))
    assertEquals(Color(0xFF2196F3), HaStateColor.activeFor(state("switch.k", "off")))
    assertEquals(Color(0xFF43A047), HaStateColor.activeFor(state("lock.door", "unlocked")))
    assertEquals(Color(0xFF673AB7), HaStateColor.activeFor(state("media_player.tv", "idle")))
  }

  @Test
  fun activeForClimateDependsOnMode() {
    assertEquals(Color(0xFFE65100), HaStateColor.activeFor(state("climate.h", "heat")))
    assertEquals(Color(0xFF2196F3), HaStateColor.activeFor(state("climate.h", "cool")))
    assertEquals(grayOff, HaStateColor.activeFor(state("climate.h", "off")))
  }

  @Test
  fun activeForUnmodeledDomainIsInactiveGray() {
    assertEquals(grayInactive, HaStateColor.activeFor(state("scene.movie", "on")))
  }

  // --- inactiveFor ---------------------------------------------------------

  @Test
  fun inactiveForLockIsAlertRed() {
    // A lock's "inactive" (unlocked) state is an alert colour, not gray.
    assertEquals(Color(0xFFE53935), HaStateColor.inactiveFor(state("lock.door", "locked")))
  }

  @Test
  fun inactiveForSensorKeepsDeviceClassColorOthersAreGray() {
    assertEquals(Color(0xFF43A047), HaStateColor.inactiveFor(state("sensor.b", "80", "battery")))
    assertEquals(grayOff, HaStateColor.inactiveFor(state("light.k", "on")))
  }
}
