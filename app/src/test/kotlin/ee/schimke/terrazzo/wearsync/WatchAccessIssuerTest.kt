package ee.schimke.terrazzo.wearsync

import ee.schimke.ha.client.DashboardSummary
import ee.schimke.ha.client.HaInstanceConfig
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.core.session.SessionConnectionStatus
import ee.schimke.terrazzo.wearsync.proto.WearAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

/**
 * Each watch gets a token of its own, named for it; a watch that asks before the phone is connected
 * is answered when it connects; signing out deletes only the watches' tokens.
 */
class WatchAccessIssuerTest {
  private class FakeSession(
    status: SessionConnectionStatus = SessionConnectionStatus.Connected,
    private val canIssue: Boolean = true,
  ) : HaSession {
    override val baseUrl = "http://homeassistant.local:8123"
    override val connectionStatus = MutableStateFlow(status)

    /** The user's long-lived tokens, by name. */
    val tokens = linkedMapOf("Grafana" to "grafana-token")
    private var minted = 0

    override suspend fun createLongLivedToken(clientName: String, lifespanDays: Int): String? {
      if (!canIssue) return null
      check(clientName !in tokens) { "$clientName already exists" }
      return "token-${++minted}".also { tokens[clientName] = it }
    }

    override suspend fun deleteLongLivedTokens(matches: (String) -> Boolean): Int {
      val gone = tokens.keys.filter(matches)
      gone.forEach(tokens::remove)
      return gone.size
    }

    override suspend fun fetchInstanceConfig() =
      HaInstanceConfig(externalUrl = "https://home.example.org/")

    override suspend fun connect() = Unit

    override suspend fun listDashboards(): List<DashboardSummary> = emptyList()

    override suspend fun loadDashboard(urlPath: String?): Pair<Dashboard, HaSnapshot> =
      error("unused")

    override suspend fun close() = Unit
  }

  private var clock = 1_000_000L
  private val sent = mutableListOf<Pair<String, WearAccess>>()
  private val issuer =
    WatchAccessIssuer(
      send = { node, access -> sent += node to access },
      nodeName = { node -> mapOf("w1" to "Pixel Watch 3", "w2" to "Galaxy Watch")[node] },
      now = { clock },
    )

  @Test
  fun aWatchGetsItsOwnNamedTokenAndWhereToReachHomeAssistant() = runTest {
    val session = FakeSession()
    issuer.request("w1", session)
    issuer.request("w2", session)
    assertEquals(listOf("w1", "w2"), sent.map { it.first })
    val access = sent.first().second
    assertEquals("http://homeassistant.local:8123", access.baseUrl)
    assertEquals("https://home.example.org", access.externalUrl)
    assertEquals(session.tokens.getValue("Terrazzo Watch · Pixel Watch 3"), access.token)
    assertEquals(
      setOf("Grafana", "Terrazzo Watch · Pixel Watch 3", "Terrazzo Watch · Galaxy Watch"),
      session.tokens.keys,
    )
  }

  @Test
  fun askingAgainSoonGetsNothingNewAndLaterReplacesItsToken() = runTest {
    val session = FakeSession()
    issuer.request("w1", session)
    issuer.request("w1", session)
    assertEquals(1, sent.size, "a watch asking in a loop isn't sent a token each time")
    clock += WatchAccessIssuer.REISSUE_AFTER_MS
    issuer.request("w1", session)
    assertEquals(listOf("token-1", "token-2"), sent.map { it.second.token })
    assertEquals("token-2", session.tokens.getValue("Terrazzo Watch · Pixel Watch 3"))
  }

  @Test
  fun aWatchThatAsksBeforeThePhoneConnectsIsAnsweredWhenItDoes() = runTest {
    issuer.request("w1", null)
    val session = FakeSession(SessionConnectionStatus.Connecting)
    issuer.request("w2", session)
    assertTrue(sent.isEmpty())
    session.connectionStatus.value = SessionConnectionStatus.Connected
    issuer.onConnected(session)
    assertEquals(listOf("w1", "w2"), sent.map { it.first })
    issuer.onConnected(session)
    assertEquals(2, sent.size, "answered once")
  }

  @Test
  fun aSessionThatCantIssueKeepsTheWatchWaiting() = runTest {
    issuer.request("w1", FakeSession(canIssue = false))
    assertTrue(sent.isEmpty())
    issuer.onConnected(FakeSession())
    assertEquals(listOf("w1"), sent.map { it.first })
  }

  @Test
  fun signingOutDeletesOnlyTheWatchesTokens() = runTest {
    val session = FakeSession()
    issuer.request("w1", session)
    issuer.request("w2", session)
    session.tokens["Terrazzo Watchtower"] = "someone-elses"
    issuer.revokeAll(session)
    assertEquals(setOf("Grafana", "Terrazzo Watchtower"), session.tokens.keys)
  }

  @Test
  fun anUnnamedWatchGetsThePlainName() {
    assertEquals("Terrazzo Watch", WatchAccessIssuer.tokenName(null))
    assertEquals("Terrazzo Watch", WatchAccessIssuer.tokenName("  "))
    assertTrue(WatchAccessIssuer.isWatchToken(WatchAccessIssuer.tokenName(null)))
  }
}
