package ee.schimke.ha.rc.cards

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.HistoryPoint
import ee.schimke.ha.rc.cardDataSignature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The sensor card bakes its sparkline from `snapshot.history`, which no host pushes as a binding,
 * so a captured sensor card must re-encode when the history it draws moves (live-card-updates.md,
 * path 2).
 */
class SensorCardLiveDataTest {
  private val registry = defaultRegistry()
  private val id = "sensor.temp"
  private val card =
    CardConfig(
      "sensor",
      JsonObject(mapOf("type" to JsonPrimitive("sensor"), "entity" to JsonPrimitive(id))),
    )

  private fun snapshot(state: String, vararg history: String) =
    HaSnapshot(
      states = mapOf(id to EntityState(id, state)),
      history =
        mapOf(
          id to
            history.mapIndexed { i, v ->
              HistoryPoint(Instant.fromEpochMilliseconds(i * 3_600_000L), v)
            }
        ),
    )

  @Test
  fun aNewHistorySampleReEncodesTheSparkline() {
    val before = assertNotNull(registry.cardDataSignature(card, snapshot("21.5", "20.0", "21.5")))
    val after = registry.cardDataSignature(card, snapshot("21.5", "20.0", "21.5", "23.0"))
    assertNotEquals(before, after, "the baked sparkline must follow new history")
    assertEquals(before, registry.cardDataSignature(card, snapshot("21.5", "20.0", "21.5")))
  }

  @Test
  fun aNewReadingReEncodesTheCard() {
    // A re-encode card takes no binding push, so its value is in the signature as well.
    val before = assertNotNull(registry.cardDataSignature(card, snapshot("21.5", "20.0", "21.5")))
    assertNotEquals(before, registry.cardDataSignature(card, snapshot("22.0", "20.0", "21.5")))
  }
}
