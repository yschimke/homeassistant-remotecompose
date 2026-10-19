package ee.schimke.ha.rc.cards

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cardDataSignature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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

  private fun regexCard(pattern: String): CardConfig {
    val filter =
      JsonObject(mapOf("operator" to JsonPrimitive("regex"), "value" to JsonPrimitive(pattern)))
    return CardConfig(
      "entity-filter",
      JsonObject(card().raw + ("state_filter" to JsonArray(listOf(filter)))),
    )
  }

  @Test
  fun aRegexFilterSearchesTheStateAsHomeAssistantsRegExpTestDoes() {
    // HA evaluates `RegExp(value).test(state)`: a match anywhere in the state counts.
    assertEquals(listOf("lock.front", "lock.back"), filteredEntities(regexCard("lock"), snapshot))
    assertEquals(
      listOf("cover.garage", "valve.garden"),
      filteredEntities(regexCard("^open"), snapshot),
    )
    // Anchoring both ends still asks for the whole state.
    assertEquals(listOf("valve.garden"), filteredEntities(regexCard("^open$"), snapshot))
  }

  private val registry = defaultRegistry()

  private fun withState(id: String, state: String) =
    snapshot.copy(states = snapshot.states + (id to EntityState(id, state)))

  @Test
  fun anEntityStartingOrStoppingToMatchReEncodesTheCard() {
    val base = assertNotNull(registry.cardDataSignature(card(), snapshot))
    // light.hall turning on joins the filtered glance: the captured document must be rebuilt.
    assertNotEquals(base, registry.cardDataSignature(card(), withState("light.hall", "on")))
    // light.kitchen turning off leaves it.
    assertNotEquals(base, registry.cardDataSignature(card(), withState("light.kitchen", "off")))
    assertEquals(base, registry.cardDataSignature(card(), snapshot), "deterministic")
  }

  @Test
  fun aShownEntitysNewValueReEncodesTheCardToo() {
    // A re-encode card takes no binding push, so a row's value is in the signature as well.
    val filtered = card(listOf("21.5", "22.0"))
    val base = assertNotNull(registry.cardDataSignature(filtered, snapshot))
    assertNotEquals(base, registry.cardDataSignature(filtered, withState("sensor.temp", "22.0")))
  }
}
