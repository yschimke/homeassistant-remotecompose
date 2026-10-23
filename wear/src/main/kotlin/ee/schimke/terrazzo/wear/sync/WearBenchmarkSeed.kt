package ee.schimke.terrazzo.wear.sync

import android.content.Context
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import ee.schimke.terrazzo.wearsync.proto.LiveValues
import ee.schimke.terrazzo.wearsync.proto.PinnedCard
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import ee.schimke.terrazzo.wearsync.proto.PinnedSection
import ee.schimke.terrazzo.wearsync.proto.PinnedSectionSet
import ee.schimke.terrazzo.wearsync.proto.WearSettings

/**
 * What a phone in demo mode would have synced to the watch: a pinned section and a dozen pinned
 * cards, with their values. `:wear-benchmark` launches with it so a cold start and a scroll measure
 * real cards; an unpaired emulator otherwise has nothing pinned. Written to [WearOfflineStore], the
 * cache a watch launches from, before the sync repository reads it.
 */
internal object WearBenchmarkSeed {

  fun write(context: Context) {
    val store = WearOfflineStore(context)
    store.writeSettings(WearSettings(demoMode = true, updatedAtMs = SEEDED_AT))
    store.writeValues(LiveValues(values = values, capturedAtMs = SEEDED_AT))
    store.writeSections(
      PinnedSectionSet(
        sections =
          listOf(
            PinnedSection(
              dashboardUrlPath = "demo",
              title = "Living room",
              cards = listOf(card(LIGHT_LIVING), card(THERMOSTAT), card(MEDIA), card(BLINDS)),
              sectionKey = "living-room",
              orderIndex = 0,
            )
          ),
        updatedAtMs = SEEDED_AT,
      )
    )
    store.writePinned(
      PinnedCardSet(
        cards =
          PINNED.mapIndexed { index, json ->
            PinnedCard(card = card(json), cardKey = "card-$index", orderIndex = index + 1)
          },
        updatedAtMs = SEEDED_AT,
      )
    )
  }

  private fun card(json: String): CardSummary {
    val type = Regex("\"type\":\"([^\"]+)\"").find(json)!!.groupValues[1]
    val entity = Regex("\"entity\":\"([^\"]+)\"").find(json)?.groupValues?.get(1).orEmpty()
    return CardSummary(type = type, primaryEntityId = entity, rawJson = json)
  }

  /** Older than anything a real phone sends, so a paired watch's sync replaces it. */
  private const val SEEDED_AT = 1L

  private const val LIGHT_LIVING = """{"type":"light","entity":"light.living_room"}"""
  private const val THERMOSTAT = """{"type":"thermostat","entity":"climate.living_room"}"""
  private const val MEDIA = """{"type":"tile","entity":"media_player.living_room"}"""
  private const val BLINDS = """{"type":"tile","entity":"cover.living_room_blinds"}"""

  private val PINNED =
    listOf(
      """{"type":"heading","heading":"Home"}""",
      """{"type":"tile","entity":"light.kitchen"}""",
      """{"type":"entity","entity":"sensor.outside_temp"}""",
      """{"type":"gauge","entity":"sensor.humidity","min":0,"max":100}""",
      """{"type":"button","entity":"switch.fan"}""",
      """{"type":"sensor","entity":"sensor.power","hours_to_show":24}""",
      """{"type":"tile","entity":"lock.front_door"}""",
      """{"type":"entities","title":"Downstairs","entities":["light.hallway","binary_sensor.back_door","sensor.hallway_temp"]}""",
      """{"type":"gauge","entity":"sensor.battery","min":0,"max":100}""",
      """{"type":"tile","entity":"cover.garage_door"}""",
      """{"type":"markdown","title":"Notes","content":"Bins go out on Thursday."}""",
      """{"type":"entity","entity":"sensor.co2"}""",
    )

  private val values: Map<String, EntityValue> =
    mapOf(
      "light.living_room" to EntityValue("on", "Living room"),
      "climate.living_room" to EntityValue("heat", "Living room", deviceClass = ""),
      "media_player.living_room" to EntityValue("playing", "Living room speaker"),
      "cover.living_room_blinds" to EntityValue("open", "Blinds", deviceClass = "blind"),
      "light.kitchen" to EntityValue("on", "Kitchen"),
      "sensor.outside_temp" to
        EntityValue("12.4", "Outside", "°C", "temperature", displayPrecision = 1),
      "sensor.humidity" to EntityValue("54", "Humidity", "%", "humidity"),
      "switch.fan" to EntityValue("off", "Fan"),
      "sensor.power" to EntityValue("412", "Power", "W", "power"),
      "lock.front_door" to EntityValue("locked", "Front door"),
      "light.hallway" to EntityValue("off", "Hallway"),
      "binary_sensor.back_door" to EntityValue("off", "Back door", deviceClass = "door"),
      "sensor.hallway_temp" to
        EntityValue("19.8", "Hallway", "°C", "temperature", displayPrecision = 1),
      "sensor.battery" to EntityValue("81", "Phone battery", "%", "battery"),
      "cover.garage_door" to EntityValue("closed", "Garage", deviceClass = "garage"),
      "sensor.co2" to EntityValue("640", "CO₂", "ppm", "carbon_dioxide"),
    )
}
