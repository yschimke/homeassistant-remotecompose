package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.Sha256
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
  fun imagesAreScopedToTheSessionAndExpireWithIt() {
    var now = 0L
    val sessions = AgentDesignSessions { now }
    val card = Json.parseToJsonElement("""{"type":"tile","entity":"light.s0"}""").jsonObject
    val original =
      UiBuilderSeed.document(CardSchema.fromCard(card), "card-seed", "tile", HaCardsSeed.card(null))
    val first = sessions.create("Redesign", original)
    val other = sessions.create("Another", original)
    val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1)
    assertFalse(sessions.putAsset(first.owner, "door", png))
    assertTrue(sessions.putAsset(first.agent, "door", png))
    png[8] = 2
    val asset = assertNotNull(sessions.asset(first.owner, "door"))
    assertEquals("image/png", asset.mediaType)
    assertContentEquals(
      byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1),
      asset.bytes,
    )
    asset.bytes[8] = 3
    assertEquals(1, sessions.asset(first.owner, "door")!!.bytes[8].toInt())
    assertNull(sessions.asset(other.owner, "door"))
    assertFailsWith<IllegalArgumentException> { sessions.putAsset(first.agent, "../door", png) }
    assertFailsWith<IllegalArgumentException> {
      sessions.putAsset(first.agent, "door", byteArrayOf(1))
    }
    assertTrue(sessions.revoke(first.owner))
    assertNull(sessions.asset(first.agent, "door"))
    assertTrue(sessions.putAsset(other.agent, "door", png))
    now = 60 * 60_000L
    assertNull(sessions.asset(other.owner, "door"))
  }

  @Test
  fun submittedAssetBindingsMustMatchTheImagesTheAgentAttached() {
    val sessions = AgentDesignSessions()
    val card = Json.parseToJsonElement("""{"type":"tile","entity":"light.s0"}""").jsonObject
    val original =
      UiBuilderSeed.document(CardSchema.fromCard(card), "card-seed", "tile", HaCardsSeed.card(null))
    val access = sessions.create("Redesign", original)
    val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1)
    val digest = "sha256:${Sha256.hex(png)}"
    val binding =
      Json.parseToJsonElement(
        """{"mediaType":"image/png","contentDigest":"$digest","source":{"type":"uploaded","storageKey":"$digest"}}"""
      )
    val candidate = JsonObject(original + ("assets" to JsonObject(mapOf("door" to binding))))
    assertFailsWith<IllegalArgumentException> { sessions.submit(access.agent, 0, candidate) }
    sessions.putAsset(access.agent, "door", png)
    val bad =
      JsonObject(
        original +
          ("assets" to
            JsonObject(
              mapOf(
                "door" to
                  Json.parseToJsonElement(binding.toString().replace("image/png", "image/jpeg"))
              )
            ))
      )
    assertFailsWith<IllegalArgumentException> { sessions.submit(access.agent, 0, bad) }
    assertTrue(sessions.submit(access.agent, 0, candidate))
  }

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
