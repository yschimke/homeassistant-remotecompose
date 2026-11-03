package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.client.dashboard.AgentEditSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DirectPresenceTest {
  private val session =
    AgentEditSession(
      id = "s1",
      agent = AgentEditSession.Activity("Drafting", seenAt = 100.0),
      serverTime = 110.0,
    )

  @Test
  fun anAgentThatWentQuietStopsShowingAsConnected() {
    assertEquals(true, directLive(session)?.agent?.connected)
    // No session event comes when an agent stops: elapsed time alone ages it.
    val later = directLive(session, sinceReply = 120.0)
    assertEquals(false, later?.agent?.connected)
    assertEquals(130L, later?.agent?.lastSeenSecondsAgo)
  }

  @Test
  fun beforeTheAgentIsActiveThereIsNoLivePanel() {
    assertNull(directLive(AgentEditSession(id = "s1")))
  }
}
