package ee.schimke.ha.rc

import androidx.compose.ui.graphics.Color
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaLiveBindings
import ee.schimke.ha.model.toTyped
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * State colour follows state: an entity's active, inactive and unavailable looks never share a
 * colour, and a binary sensor's device class only colours it while it is on.
 */
class HaStateColorTest {

  private fun state(entityId: String, state: String, deviceClass: String? = null) =
    EntityState(
      entityId = entityId,
      state = state,
      attributes = buildJsonObject { if (deviceClass != null) put("device_class", deviceClass) },
    )

  /**
   * The look a component draws for [entity]: what [HaStateColor.toggleAccentFor] seeds and the live
   * bindings then select — unavailable wins, then on/off for an entity with an on/off concept.
   */
  private enum class Look {
    Active,
    Inactive,
    Unavailable,
  }

  private fun lookOf(entity: EntityState): Look =
    when {
      !HaLiveBindings.isAvailable(entity.state) -> Look.Unavailable
      HaLiveBindings.isOn(entity.entityId, entity.state) -> Look.Active
      entity.toTyped().isActive == null -> Look.Active // sensors: always their colour
      else -> Look.Inactive
    }

  // --- the audit cases -----------------------------------------------------

  @Test
  fun motionOffIsNeutralNotTheMotionColour() {
    val off = state("binary_sensor.front_room", "off", "motion")
    assertEquals(Look.Inactive, lookOf(off))
    assertNull(HaStateColor.inactiveFor(off), "inactive motion takes the theme neutral")
    assertEquals(HaStateColor.Neutral, HaStateColor.resolve(off))
  }

  @Test
  fun motionUnavailableIsUnavailableNotActive() {
    val unavailable = state("binary_sensor.driveway", "unavailable", "motion")
    assertEquals(Look.Unavailable, lookOf(unavailable))
    assertNull(HaStateColor.inactiveFor(unavailable))
    assertEquals(HaStateColor.Unavailable, HaStateColor.resolve(unavailable))
  }

  @Test
  fun motionOnIsAmber() {
    val on = state("binary_sensor.hall", "on", "motion")
    assertEquals(Look.Active, lookOf(on))
    assertEquals(HaStateColor.Amber, HaStateColor.activeFor(on))
    assertEquals(HaStateColor.Amber, HaStateColor.resolve(on))
    assertEquals(
      HaStateColor.Amber,
      HaStateColor.activeFor(state("binary_sensor.o", "on", "occupancy")),
    )
  }

  @Test
  fun connectivityIsGreenOnlyWhenConnected() {
    val on = state("binary_sensor.broker", "on", "connectivity")
    val off = state("binary_sensor.broker", "off", "connectivity")
    assertEquals(HaStateColor.Green, HaStateColor.resolve(on))
    assertEquals(Look.Inactive, lookOf(off))
    assertNull(HaStateColor.inactiveFor(off), "a disconnected broker is not green")
    assertEquals(HaStateColor.Neutral, HaStateColor.resolve(off))
  }

  @Test
  fun problemOnIsTheAlertColourAndOffIsNeutral() {
    val on = state("binary_sensor.hms_errors", "on", "problem")
    val off = state("binary_sensor.hms_errors", "off", "problem")
    assertEquals(HaStateColor.Alert, HaStateColor.activeFor(on))
    assertEquals(HaStateColor.Alert, HaStateColor.resolve(on))
    assertEquals(HaStateColor.Neutral, HaStateColor.resolve(off))
    assertNull(HaStateColor.inactiveFor(off))
    // A problem is not styled like an ordinary "on" (amber), nor like connected (green).
    assertNotEquals(HaStateColor.activeFor(state("binary_sensor.online", "on")), HaStateColor.Alert)
  }

  @Test
  fun everyTroubleDeviceClassIsTheAlertColourWhenOn() {
    for (dc in
      listOf(
        "battery",
        "carbon_monoxide",
        "gas",
        "heat",
        "lock",
        "moisture",
        "problem",
        "safety",
        "smoke",
        "sound",
        "tamper",
        "update",
        "vibration",
      )) {
      assertEquals(
        HaStateColor.Alert,
        HaStateColor.activeFor(state("binary_sensor.x", "on", dc)),
        dc,
      )
    }
  }

  @Test
  fun nonBooleanBinaryStatesAreNeutral() {
    // `stale`, `fresh`, `Active`: a binary sensor state with no defined on mapping is not active.
    for (raw in listOf("stale", "fresh", "Active")) {
      val entity = state("binary_sensor.contact", raw, "connectivity")
      assertEquals(Look.Inactive, lookOf(entity), raw)
      assertNull(HaStateColor.inactiveFor(entity), raw)
      assertEquals(HaStateColor.Neutral, HaStateColor.resolve(entity), raw)
    }
  }

  @Test
  fun unknownIsUnavailableToo() {
    val unknown = state("light.k", "unknown")
    assertEquals(Look.Unavailable, lookOf(unknown))
    assertEquals(HaStateColor.Unavailable, HaStateColor.resolve(unknown))
    assertEquals(Look.Unavailable, lookOf(state("sensor.t", "unavailable", "temperature")))
  }

  @Test
  fun theThreeLooksNeverShareAColour() {
    for (dc in listOf(null, "motion", "connectivity", "problem", "door", "moisture")) {
      val on = HaStateColor.resolve(state("binary_sensor.x", "on", dc))
      val off = HaStateColor.resolve(state("binary_sensor.x", "off", dc))
      val gone = HaStateColor.resolve(state("binary_sensor.x", "unavailable", dc))
      assertEquals(3, setOf(on, off, gone).size, "device_class=$dc: $on / $off / $gone")
    }
  }

  // --- availability --------------------------------------------------------

  @Test
  fun availabilityFollowsTheState() {
    assertFalse(HaLiveBindings.isAvailable("unavailable"))
    assertFalse(HaLiveBindings.isAvailable("unknown"))
    for (raw in listOf("on", "off", "stale", "21.5", "home")) {
      assertTrue(HaLiveBindings.isAvailable(raw), raw)
    }
  }

  // --- resolve / activeFor for the other domains ---------------------------

  @Test
  fun resolveNullEntityIsUnavailable() {
    assertEquals(HaStateColor.Unavailable, HaStateColor.resolve(null))
  }

  @Test
  fun resolveSensorUsesDeviceClassColor() {
    assertEquals(Color(0xFF2196F3), HaStateColor.resolve(state("sensor.t", "21.0", "temperature")))
    assertEquals(Color(0xFFFFA000), HaStateColor.resolve(state("sensor.p", "120", "power")))
    assertEquals(Color(0xFF546E7A), HaStateColor.resolve(state("sensor.x", "5")))
    assertEquals(Color(0xFF546E7A), HaStateColor.resolve(state("sensor.x", "5", "nonsense")))
  }

  @Test
  fun resolveTrackerIsGreenWhenHomeNeutralWhenAway() {
    assertEquals(HaStateColor.Green, HaStateColor.resolve(state("person.a", "home")))
    assertEquals(HaStateColor.Neutral, HaStateColor.resolve(state("device_tracker.a", "not_home")))
  }

  @Test
  fun resolveToggleableUsesActiveColorOnlyWhenOn() {
    assertEquals(Color(0xFFFFBE3E), HaStateColor.resolve(state("light.k", "on")))
    assertEquals(HaStateColor.Neutral, HaStateColor.resolve(state("light.k", "off")))
  }

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
    assertEquals(HaStateColor.Neutral, HaStateColor.activeFor(state("climate.h", "off")))
  }

  @Test
  fun activeForUnmodeledDomainIsUnavailableGray() {
    assertEquals(HaStateColor.Unavailable, HaStateColor.activeFor(state("scene.movie", "on")))
  }

  @Test
  fun inactiveForLockIsAlertRedEverythingElseNeutral() {
    // A lock's "inactive" (unlocked) state is an alert colour, not neutral.
    assertEquals(HaStateColor.Alert, HaStateColor.inactiveFor(state("lock.door", "locked")))
    assertEquals(HaStateColor.Alert, HaStateColor.resolve(state("lock.door", "unlocked")))
    assertNull(HaStateColor.inactiveFor(state("light.k", "on")))
    assertNull(HaStateColor.inactiveFor(state("switch.k", "off")))
    assertNull(HaStateColor.inactiveFor(state("binary_sensor.d", "off", "door")))
  }
}
