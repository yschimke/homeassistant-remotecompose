package ee.schimke.ha.client

import io.ktor.client.engine.mock.MockEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class HaClientHostedAuthTest {
  @Test
  fun failedSessionRefreshDoesNotFallBackToTheConfiguredToken() = runTest {
    var requests = 0
    val client =
      HaClient(
        HaConfig("https://ha.test", "stale-fixture-token"),
        MockEngine {
          requests++
          error("A failed session refresh must not open a socket")
        },
        accessTokenProvider = { error("Home Assistant session expired") },
      )
    try {
      val error = assertFailsWith<IllegalStateException> { client.connect() }
      assertEquals("Home Assistant session expired", error.message)
      assertEquals(0, requests)
    } finally {
      client.close()
    }
  }
}
