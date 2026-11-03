package ee.schimke.terrazzo.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CardAgentSessionsTest {
  @Test
  fun sessionsAreScopedToCardAndHomeAndExpire() {
    CardAgentSessions.clear()
    val session = SavedCardAgentSession("https://design.example", "owner", "make it", 1000)
    CardAgentSessions.put("home-a:card-a", session)
    assertEquals(session, CardAgentSessions.get("home-a:card-a", 999))
    assertNull(CardAgentSessions.get("home-b:card-a", 999))
    assertNull(CardAgentSessions.get("home-a:card-b", 999))
    assertNull(CardAgentSessions.get("home-a:card-a", 1000))
    assertNull(CardAgentSessions.get("home-a:card-a", 999))
  }

  @Test
  fun revocationAndSignOutRemoveSessions() {
    CardAgentSessions.clear()
    val session = SavedCardAgentSession("https://design.example", "owner", "", Long.MAX_VALUE)
    CardAgentSessions.put("one", session)
    CardAgentSessions.put("two", session)
    CardAgentSessions.remove("one")
    assertNull(CardAgentSessions.get("one"))
    assertEquals(session, CardAgentSessions.get("two"))
    CardAgentSessions.clear()
    assertNull(CardAgentSessions.get("two"))
  }
}
