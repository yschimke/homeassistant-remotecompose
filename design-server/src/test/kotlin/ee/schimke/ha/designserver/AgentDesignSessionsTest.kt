package ee.schimke.ha.designserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class AgentDesignSessionsTest {
  @Test
  fun capabilitiesAreScopedRevocableAndKeepTheOriginalBindings() {
    var now = 0L
    val sessions = AgentDesignSessions { now }
    val card = Json.parseToJsonElement("""{"type":"tile","entity":"light.s0"}""").jsonObject
    val original =
      UiBuilderSeed.document(CardSchema.fromCard(card), "card-seed", "tile", HaCardsSeed.card(null))
    val access = sessions.create("Make it brighter", original)
    assertNotNull(sessions.read(access.agent))
    assertEquals("Make it brighter", sessions.read(access.owner)?.instruction)
    assertFalse(sessions.revoke(access.agent))
    assertFalse(sessions.submit(access.owner, 0, original))
    val changed = JsonObject(original + ("title" to JsonPrimitive("Brighter")))
    assertTrue(sessions.submit(access.agent, 0, changed))
    assertEquals(changed, sessions.read(access.owner)?.document)
    assertFailsWith<IllegalArgumentException> { sessions.submit(access.agent, 0, changed) }
    val broken = JsonObject(changed + ("stateVariables" to JsonObject(emptyMap())))
    assertFailsWith<IllegalArgumentException> { sessions.submit(access.agent, 1, broken) }
    assertTrue(sessions.revoke(access.owner))
    assertNull(sessions.read(access.agent))
    val expiring = sessions.create("Try again", original)
    now = 60 * 60_000L
    assertNull(sessions.read(expiring.owner))
    assertNull(sessions.read(expiring.agent))
  }
}
