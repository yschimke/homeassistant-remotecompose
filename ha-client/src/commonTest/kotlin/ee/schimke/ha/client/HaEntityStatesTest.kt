package ee.schimke.ha.client

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive

/** Chosen entities' states over REST, with the token as a bearer, and nothing else asked for. */
class HaEntityStatesTest {
  // fetch asks for entities concurrently, so the engine records them from more than one thread.
  private val requested = mutableListOf<String>()
  private val requestedLock = Mutex()

  private fun engine(status: HttpStatusCode = HttpStatusCode.OK) = MockEngine { request ->
    requestedLock.withLock { requested += request.url.encodedPath }
    assertEquals("Bearer llat", request.headers[HttpHeaders.Authorization])
    val id = request.url.encodedPath.removePrefix("/api/states/")
    when {
      status != HttpStatusCode.OK -> respond("", status)
      id == "sensor.gone" -> respond("""{"message":"Entity not found."}""", HttpStatusCode.NotFound)
      else ->
        respond(
          """{"entity_id":"$id","state":"21.5","attributes":{"unit_of_measurement":"°C"},""" +
            """"last_changed":"2026-10-22T08:00:00+00:00"}""",
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }
  }

  @Test
  fun eachEntityIsReadByItsOwnPathAndMissingOnesAreLeftOut() = runTest {
    val states =
      HaEntityStates("https://ha.example/", "llat", engine()).use {
        it.fetch(listOf("sensor.outside", "sensor.gone", "sensor.outside", "../config", "light"))
      }
    assertEquals(setOf("sensor.outside"), states.keys)
    assertEquals("21.5", states.getValue("sensor.outside").state)
    assertEquals(
      "°C",
      states
        .getValue("sensor.outside")
        .attributes
        .getValue("unit_of_measurement")
        .jsonPrimitive
        .content,
    )
    // Once per entity id; the ids that aren't entity ids are never sent.
    assertEquals(
      listOf("/api/states/sensor.gone", "/api/states/sensor.outside"),
      requested.sorted(),
    )
  }

  @Test
  fun aRefusedTokenIsAnAuthFailure() = runTest {
    HaEntityStates("https://ha.example", "llat", engine(HttpStatusCode.Unauthorized)).use {
      assertFailsWith<HaAuthInvalidException> { it.fetch(listOf("sensor.outside")) }
    }
  }

  @Test
  fun anyOtherFailureFails() = runTest {
    HaEntityStates("https://ha.example", "llat", engine(HttpStatusCode.BadGateway)).use {
      assertFailsWith<IllegalStateException> { it.fetch(listOf("sensor.outside")) }
    }
  }

  @Test
  fun historyAsksForOneEntitysMinimalChangesAndReadsThemOldestFirst() = runTest {
    var query: io.ktor.http.Parameters? = null
    var path: String? = null
    val engine = MockEngine { request ->
      assertEquals("Bearer llat", request.headers[HttpHeaders.Authorization])
      path = request.url.encodedPath
      query = request.url.parameters
      respond(
        """[[{"entity_id":"light.hall","state":"off","last_changed":"2026-10-24T12:00:00+00:00",""" +
          """"last_updated":"2026-10-24T12:00:00+00:00"},""" +
          """{"state":"on","last_changed":"2026-10-24T18:30:00.5+00:00"},""" +
          """{"state":"off"}]]""",
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
      )
    }
    val start = kotlin.time.Instant.parse("2026-10-24T12:00:00Z")
    val points =
      HaEntityStates("https://ha.example", "llat", engine).use { it.history("light.hall", start) }
    assertEquals("/api/history/period/2026-10-24T12:00:00Z", path)
    assertEquals("light.hall", query?.get("filter_entity_id"))
    assertEquals(true, query?.contains("minimal_response"))
    assertEquals(true, query?.contains("no_attributes"))
    assertEquals("0", query?.get("significant_changes_only"))
    // The point with no time is dropped.
    assertEquals(listOf("off", "on"), points.map { it.state })
    assertEquals(kotlin.time.Instant.parse("2026-10-24T18:30:00.5Z"), points[1].ts)
  }

  @Test
  fun historyOfANonEntityIdAsksNothing() = runTest {
    val engine = MockEngine { error("asked for ${it.url}") }
    assertEquals(
      emptyList(),
      HaEntityStates("https://ha.example", "llat", engine).use {
        it.history("../config", kotlin.time.Instant.parse("2026-10-24T12:00:00Z"))
      },
    )
  }

  @Test
  fun aRefusedTokenFailsTheHistoryAsAuth() = runTest {
    assertFailsWith<HaAuthInvalidException> {
      HaEntityStates("https://ha.example", "llat", engine(HttpStatusCode.Unauthorized)).use {
        it.history("light.hall", kotlin.time.Instant.parse("2026-10-24T12:00:00Z"))
      }
    }
  }
}
