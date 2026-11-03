package ee.schimke.ha.client.agent

import ee.schimke.ha.client.NetworkUnreachableException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class AgentRelayClientTest {
  private fun state(version: Long, revision: Long = 0) =
    """{"version":$version,"kind":"dashboard","revision":$revision,"agent":{"joined":true}}"""

  @Test
  fun aFailedFetchIsAnExceptionTheWatchRetries() = runTest {
    var calls = 0
    // Ktor's fetch engine throws a Kotlin Error when the server can't be reached; so does this.
    val engine = MockEngine { _ ->
      calls++
      if (calls == 2) throw Error("Fail to fetch")
      respond(
        state(calls.toLong(), revision = if (calls >= 3) 1 else 0),
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, "application/json"),
      )
    }
    val client = AgentRelayClient("https://design.example", engine)
    assertFailsWith<NetworkUnreachableException> {
      client.state("owner")
      client.state("owner")
    }
    calls = 0
    // The watch rides out the failure and carries on with the next answer.
    val seen = client.watch("owner").take(2).toList()
    assertEquals(listOf(0L, 1L), seen.map { it.revision })
    client.close()
  }

  @Test
  fun anEndedSessionEndsTheWatch() = runTest {
    val engine = MockEngine { _ -> respond("gone", HttpStatusCode.NotFound) }
    val client = AgentRelayClient("https://design.example", engine)
    assertFailsWith<AgentSessionEndedException> { client.watch("owner").first() }
    client.close()
  }
}
