package ee.schimke.ha.rc.cards

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class EntityFilterCardConverterTest {
  private val states =
    mapOf(
      "light.kitchen" to "on",
      "light.hall" to "off",
      "lock.front" to "locked",
      "lock.back" to "unlocked",
      "climate.living_room" to "heat",
      "climate.bedroom" to "off",
      "cover.garage" to "opening",
      "valve.garden" to "open",
      "sensor.temp" to "21.5",
    )
  private val snapshot = HaSnapshot(states = states.mapValues { (id, s) -> EntityState(id, s) })

  private fun card(stateFilter: List<String>? = null) =
    CardConfig(
      type = "entity-filter",
      raw =
        JsonObject(
          buildMap {
            put("type", JsonPrimitive("entity-filter"))
            put("entities", JsonArray(states.keys.map(::JsonPrimitive)))
            stateFilter?.let { put("state_filter", JsonArray(it.map(::JsonPrimitive))) }
          }
        ),
    )

  @Test
  fun withoutAStateFilterShowsTheEntitiesTilesTreatAsActive() {
    assertEquals(
      listOf("light.kitchen", "lock.front", "climate.living_room", "cover.garage", "valve.garden"),
      filteredEntities(card(), snapshot),
    )
  }

  @Test
  fun anExplicitStateFilterStillMatchesRawStates() {
    assertEquals(listOf("lock.back"), filteredEntities(card(listOf("unlocked")), snapshot))
  }

  @Test
  fun anInvalidRegexFilterMatchesNothingInsteadOfThrowing() {
    val filter =
      JsonObject(mapOf("operator" to JsonPrimitive("regex"), "value" to JsonPrimitive("(")))
    val raw = JsonObject(card().raw + ("state_filter" to JsonArray(listOf(filter))))
    assertEquals(emptyList<String>(), filteredEntities(CardConfig("entity-filter", raw), snapshot))
  }

  @Test
  fun aValidRegexFilterStillMatches() {
    val filter =
      JsonObject(mapOf("operator" to JsonPrimitive("regex"), "value" to JsonPrimitive("un.*")))
    val raw = JsonObject(card().raw + ("state_filter" to JsonArray(listOf(filter))))
    assertEquals(listOf("lock.back"), filteredEntities(CardConfig("entity-filter", raw), snapshot))
  }
}
