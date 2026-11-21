package ee.schimke.ha.addon.routes

import ee.schimke.ha.addon.auth.TokenCheck
import ee.schimke.ha.addon.auth.TokenValidator
import ee.schimke.ha.addon.bridge.HaSupervisorBridge
import ee.schimke.ha.addon.configure
import ee.schimke.ha.client.AddonCapabilities
import ee.schimke.ha.client.AddonClient
import ee.schimke.ha.client.AddonInfo
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** `/v1/info` answers what this build serves, and the library client reads it. */
class InfoRouteTest {
  private val bridge = HaSupervisorBridge("http://ha.invalid", "supervisor-token")

  @AfterTest fun closeBridge() = runBlocking { bridge.close() }

  private fun app(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
    testApplication {
      application {
        configure(bridge, version = "0.1.99") {
          trustedProxyAddresses = emptySet()
          tokenValidator = TokenValidator {
            if (it == "good-token") TokenCheck.Valid else TokenCheck.Invalid
          }
        }
      }
      block()
    }

  @Test
  fun answersWhatThisBuildServes() = app {
    val info = AddonClient("http://localhost", "good-token", engine = client.engine).info()
    assertEquals(
      AddonInfo(
        protocol = 1,
        minClient = 1,
        version = "0.1.99",
        capabilities = AddonCapabilities(dashboards = 1, snapshot = 1, stream = 1),
      ),
      info,
    )
  }

  @Test
  fun writesMinClientAndLeavesOutWhatItDoesNotServe() = app {
    val body = client.get("/v1/info") { bearerAuth("good-token") }.bodyAsText()
    assertTrue("\"min_client\":1" in body, body)
    // The card endpoint answers 501 in this build: it is left out, not offered as null.
    assertFalse("cards" in body, body)
  }

  @Test
  fun needsATokenLikeEveryOtherRoute() = app {
    assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/info").status)
  }
}
