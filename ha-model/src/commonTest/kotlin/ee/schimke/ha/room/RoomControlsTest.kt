package ee.schimke.ha.room

import ee.schimke.ha.design.DeviceInfo
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.Section
import ee.schimke.ha.model.View
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class RoomControlsTest {

  private val living = HaArea("living_room", "Living Room", aliases = listOf("Lounge"))
  private val kitchen = HaArea("kitchen", "Kitchen")
  private val office = HaArea("office", "Office")

  private fun state(
    id: String,
    state: String,
    name: String? = null,
    deviceClass: String? = null,
    changed: Long? = null,
    attrs: JsonObject.() -> JsonObject = { this },
  ) =
    EntityState(
      entityId = id,
      state = state,
      attributes = buildJsonObject {
          if (name != null) put("friendly_name", name)
          if (deviceClass != null) put("device_class", deviceClass)
        }
          .attrs(),
      lastChanged = changed?.let { Instant.fromEpochSeconds(it) },
    )

  private fun card(json: String) =
    Json.parseToJsonElement(json).jsonObject.let {
      CardConfig(type = it["type"].toString().trim('"'), raw = it)
    }

  private val snapshot =
    HaSnapshot(
      states =
        listOf(
            state("climate.living_room_heater", "heat", "Living Room Heater"),
            state("media_player.living_room_tv", "playing", "Living Room TV"),
            state("light.living_room_lamp", "off", "Living Room Lamp"),
            state("switch.living_room_fan_plug", "off", "Fan plug"),
            state("switch.living_room_identify", "off", "Identify"),
            state("sensor.living_room_temperature", "21.5", "Living Room Temperature"),
            state("light.kitchen", "on", "Kitchen Lights"),
            state("binary_sensor.kitchen_motion", "on", deviceClass = "motion", changed = 200),
            state("binary_sensor.office_occupancy", "on", deviceClass = "occupancy", changed = 100),
            state("sensor.phone_area", "Lounge"),
            state("device_tracker.phone", "home"),
          )
          .associateBy { it.entityId },
      devices =
        mapOf(
          "climate.living_room_heater" to DeviceInfo(areaId = "living_room"),
          "media_player.living_room_tv" to DeviceInfo(areaId = "living_room"),
          "light.living_room_lamp" to DeviceInfo(areaId = "living_room"),
          "switch.living_room_fan_plug" to DeviceInfo(areaId = "living_room"),
          "switch.living_room_identify" to
            DeviceInfo(areaId = "living_room", entityCategory = "config"),
          "sensor.living_room_temperature" to DeviceInfo(areaId = "living_room"),
          "light.kitchen" to DeviceInfo(areaId = "kitchen"),
          "binary_sensor.kitchen_motion" to DeviceInfo(areaId = "kitchen"),
          "binary_sensor.office_occupancy" to DeviceInfo(areaId = "office"),
        ),
      areas = listOf(living, kitchen, office).associateBy { it.id },
    )

  private val dashboard =
    Dashboard(
      views =
        listOf(
          View(
            sections =
              listOf(
                Section(
                  cards =
                    listOf(
                      card(
                        """{"type":"tile","entity":"media_player.living_room_tv","features":[{"type":"media-player-volume-slider"}]}"""
                      ),
                      card("""{"type":"thermostat","entity":"climate.living_room_heater"}"""),
                      card("""{"type":"entities","entities":["light.living_room_lamp"]}"""),
                    )
                )
              )
          ),
          View(cards = listOf(card("""{"type":"tile","entity":"light.living_room_lamp"}"""))),
        )
    )

  @Test
  fun dashboardScoresControlCardsAboveRowsAndFirstViewAboveLater() {
    val importance = dashboardImportance(dashboard)
    // Tile with a feature on the first view: (3 + 1) * 1.5.
    assertEquals(6f, importance.of("media_player.living_room_tv"))
    assertEquals(4.5f, importance.of("climate.living_room_heater"))
    // A row on the first view (2 * 1.5) plus a tile on the second (3).
    assertEquals(6f, importance.of("light.living_room_lamp"))
    assertEquals(0f, importance.of("light.kitchen"))
  }

  @Test
  fun nestedCardsAndAreaCardsCount() {
    val nested =
      Dashboard(
        views =
          listOf(
            View(
              cards =
                listOf(
                  card(
                    """{"type":"vertical-stack","cards":[{"type":"conditional","card":{"type":"button","entity":"light.kitchen"}}]}"""
                  ),
                  card("""{"type":"area","area":"kitchen"}"""),
                )
            )
          )
      )
    val importance = dashboardImportance(nested)
    assertEquals(4.5f, importance.of("light.kitchen"))
    assertEquals(1.5f, importance.areas["kitchen"])
  }

  @Test
  fun roomControlsLeadWithDashboardEntitiesAndKeepTheRestReachable() {
    val controls = roomControls(living, snapshot, dashboardImportance(dashboard))
    assertEquals(
      listOf("media_player.living_room_tv", "light.living_room_lamp", "climate.living_room_heater"),
      controls.primary.map { it.entityId },
    )
    // Not on a dashboard, but still a control in the room; the config entity and the sensor aren't.
    assertEquals(listOf("switch.living_room_fan_plug"), controls.more.map { it.entityId })
  }

  @Test
  fun anActiveEntityComesFirstAtTheSameScore() {
    val controls = roomControls(living, snapshot, DashboardImportance.Empty)
    assertEquals(
      listOf(
        "climate.living_room_heater",
        "media_player.living_room_tv",
        "light.living_room_lamp",
        "switch.living_room_fan_plug",
      ),
      controls.more.map { it.entityId },
    )
    assertTrue(controls.primary.isEmpty())
  }

  @Test
  fun primaryLimitMovesTheRestToMore() {
    val controls = roomControls(living, snapshot, dashboardImportance(dashboard), primaryLimit = 1)
    assertEquals(listOf("media_player.living_room_tv"), controls.primary.map { it.entityId })
    assertEquals(
      listOf("light.living_room_lamp", "climate.living_room_heater", "switch.living_room_fan_plug"),
      controls.more.map { it.entityId },
    )
  }

  @Test
  fun namesDropTheRoomPrefix() {
    val names =
      roomControls(living, snapshot, dashboardImportance(dashboard)).primary.map { it.name }
    assertEquals(listOf("TV", "Lamp", "Heater"), names)
  }

  @Test
  fun roomsAreOrderedByWhatTheDashboardsShowInThem() {
    val rooms = roomsWithControls(snapshot, dashboardImportance(dashboard))
    // The office has only a sensor: nothing to control.
    assertEquals(listOf("living_room", "kitchen"), rooms.map { it.id })
  }

  @Test
  fun pickedRoomWinsOverTrackerAndPresence() {
    val room = currentRoom(snapshot, picked = "kitchen", trackerEntityId = "sensor.phone_area")
    assertEquals(CurrentRoom(kitchen, CurrentRoom.Source.Picked), room)
  }

  @Test
  fun trackerStateMatchesAnAreaAlias() {
    val room = currentRoom(snapshot, picked = null, trackerEntityId = "sensor.phone_area")
    assertEquals(CurrentRoom(living, CurrentRoom.Source.Tracker), room)
  }

  @Test
  fun trackerAttributeNamesTheArea() {
    val withAttribute =
      snapshot.copy(
        states =
          snapshot.states +
            ("device_tracker.phone" to
              state("device_tracker.phone", "home") {
                JsonObject(this + ("area" to kotlinx.serialization.json.JsonPrimitive("kitchen")))
              })
      )
    val room = currentRoom(withAttribute, picked = null, trackerEntityId = "device_tracker.phone")
    assertEquals(kitchen, room.area)
  }

  @Test
  fun presencePrefersOccupancyOverMoreRecentMotion() {
    val room = currentRoom(snapshot, picked = null, trackerEntityId = "device_tracker.phone")
    assertEquals(CurrentRoom(office, CurrentRoom.Source.Presence), room)
  }

  @Test
  fun noSignalsMeansNoRoom() {
    val quiet =
      snapshot.copy(states = snapshot.states.filterKeys { !it.startsWith("binary_sensor") })
    val room = currentRoom(quiet, picked = "gone", trackerEntityId = null)
    assertNull(room.area)
    assertEquals(CurrentRoom.Source.None, room.source)
  }

  @Test
  fun trackerCandidatesAreEntitiesNamingAnAreaNow() {
    assertEquals(listOf("sensor.phone_area"), roomTrackerCandidates(snapshot))
  }

  @Test
  fun areaLabelsMatchAcrossSeparatorsAndCase() {
    assertTrue(living.isNamed("living-room"))
    assertTrue(living.isNamed(" LIVING ROOM "))
    assertTrue(!living.isNamed("living"))
  }
}
