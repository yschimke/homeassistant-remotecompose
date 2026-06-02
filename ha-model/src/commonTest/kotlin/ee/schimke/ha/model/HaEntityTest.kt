package ee.schimke.ha.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class HaEntityTest {

  private fun entity(id: String, state: String, deviceClass: String? = null) =
    EntityState(
        entityId = id,
        state = state,
        attributes = buildJsonObject { if (deviceClass != null) put("device_class", deviceClass) },
      )
      .toTyped()

  @Test
  fun domainDispatchesToTheMatchingSubtype() {
    assertTrue(entity("light.k", "on") is HaEntity.Light)
    assertTrue(entity("switch.k", "on") is HaEntity.Switch)
    assertTrue(entity("input_boolean.k", "on") is HaEntity.InputBoolean)
    assertTrue(entity("cover.k", "open") is HaEntity.Cover)
    assertTrue(entity("lock.k", "locked") is HaEntity.Lock)
    assertTrue(entity("media_player.k", "playing") is HaEntity.MediaPlayer)
    assertTrue(entity("climate.k", "heat") is HaEntity.Climate)
    assertTrue(entity("alarm_control_panel.k", "armed_away") is HaEntity.AlarmControlPanel)
    assertTrue(entity("vacuum.k", "cleaning") is HaEntity.Vacuum)
    assertTrue(entity("sensor.k", "21") is HaEntity.Sensor)
    assertTrue(entity("binary_sensor.k", "on") is HaEntity.BinarySensor)
    assertTrue(entity("person.k", "home") is HaEntity.Person)
    assertTrue(entity("device_tracker.k", "home") is HaEntity.DeviceTracker)
    assertTrue(entity("weather.k", "sunny") is HaEntity.Weather)
  }

  @Test
  fun unmodeledDomainBecomesOther() {
    val other = entity("scene.movie_night", "on")
    assertTrue(other is HaEntity.Other)
    assertEquals("scene", other.domain)
  }

  @Test
  fun parsedSubtypeCarriesTypedInnerState() {
    assertEquals(OnOffState.On, (entity("light.k", "on") as HaEntity.Light).state)
    assertEquals(CoverState.Closing, (entity("cover.k", "closing") as HaEntity.Cover).state)
    assertEquals(ClimateMode.Cool, (entity("climate.k", "cool") as HaEntity.Climate).mode)
  }

  @Test
  fun accessorsDeriveFromRawEntity() {
    val e = entity("sensor.living_room_temp", "21.4", deviceClass = "temperature")
    assertEquals("sensor.living_room_temp", e.entityId)
    assertEquals("sensor", e.domain)
    assertEquals("temperature", e.deviceClass)
  }

  @Test
  fun pureSensorsHaveNoBinaryActiveConcept() {
    assertNull(entity("sensor.k", "21").isActive)
    assertNull(entity("weather.k", "sunny").isActive)
    assertNull(entity("scene.k", "on").isActive)
  }

  @Test
  fun onOffDomainsAreActiveWhenOn() {
    listOf("light.k", "switch.k", "input_boolean.k", "fan.k", "siren.k", "humidifier.k").forEach {
      id ->
      assertTrue(entity(id, "on").isActive == true, "$id on")
      assertFalse(entity(id, "off").isActive == true, "$id off")
    }
  }

  @Test
  fun coverIsActiveWhileOpenOrMoving() {
    listOf("open", "opening", "closing").forEach {
      assertTrue(entity("cover.k", it).isActive == true, "cover $it")
    }
    listOf("closed", "stopped").forEach {
      assertFalse(entity("cover.k", it).isActive == true, "cover $it")
    }
  }

  @Test
  fun mediaPlayerIsActiveOnlyWhilePlayingOnOrBuffering() {
    listOf("playing", "on", "buffering").forEach {
      assertTrue(entity("media_player.k", it).isActive == true, "media $it")
    }
    listOf("off", "idle", "standby", "paused", "unavailable", "weird").forEach {
      assertFalse(entity("media_player.k", it).isActive == true, "media $it")
    }
  }

  @Test
  fun climateIsActiveForAnyRunningMode() {
    listOf("heat", "cool", "heat_cool", "auto", "dry", "fan_only").forEach {
      assertTrue(entity("climate.k", it).isActive == true, "climate $it")
    }
    listOf("off", "unavailable").forEach {
      assertFalse(entity("climate.k", it).isActive == true, "climate $it")
    }
  }

  @Test
  fun alarmIsActiveWhenArmedOrInTransitionButNotDisarmed() {
    listOf("armed_home", "armed_away", "armed_night", "triggered", "pending", "arming").forEach {
      assertTrue(entity("alarm_control_panel.k", it).isActive == true, "alarm $it")
    }
    listOf("disarmed", "disarming", "unavailable").forEach {
      assertFalse(entity("alarm_control_panel.k", it).isActive == true, "alarm $it")
    }
  }

  @Test
  fun lockIsActiveOnlyWhenLocked() {
    assertTrue(entity("lock.k", "locked").isActive == true)
    listOf("unlocked", "jammed", "open").forEach {
      assertFalse(entity("lock.k", it).isActive == true, "lock $it")
    }
  }

  @Test
  fun vacuumIsActiveWhileCleaningOrReturning() {
    listOf("cleaning", "returning").forEach {
      assertTrue(entity("vacuum.k", it).isActive == true, "vacuum $it")
    }
    listOf("docked", "idle", "paused", "error").forEach {
      assertFalse(entity("vacuum.k", it).isActive == true, "vacuum $it")
    }
  }

  @Test
  fun presenceDomainsAreActiveWhenHome() {
    assertTrue(entity("person.a", "home").isActive == true)
    assertFalse(entity("person.a", "not_home").isActive == true)
    assertTrue(entity("device_tracker.a", "home").isActive == true)
    assertFalse(entity("device_tracker.a", "not_home").isActive == true)
  }
}
