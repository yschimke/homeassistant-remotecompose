package ee.schimke.terrazzo.core.session

import ee.schimke.ha.client.TerrazzoIntegration
import ee.schimke.ha.client.cache.OfflineCacheStorage
import ee.schimke.ha.client.dashboard.BuiltInAgent
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * [LiveHaSession]'s Terrazzo members gate on `terrazzo/info`: asked once per connection, and a
 * capability the integration leaves out is answered without a round trip. An integration older than
 * the handshake is still tried command by command.
 */
class LiveHaSessionTerrazzoTest {
  private fun unknown(id: Int) =
    """{"id":$id,"type":"result","success":false,
      "error":{"code":"unknown_command","message":"Unknown command."}}"""

  private fun ok(id: Int, result: String) =
    """{"id":$id,"type":"result","success":true,"result":$result}"""

  /** A Home Assistant that answers each command with [answer] and records its type. */
  private fun ApplicationTestBuilder.fakeHa(
    sent: MutableList<String>,
    answer: (type: String, id: Int) -> String,
  ) {
    install(WebSockets)
    routing {
      webSocket("/api/websocket") {
        send(Frame.Text("""{"type":"auth_required"}"""))
        incoming.receive()
        send(Frame.Text("""{"type":"auth_ok"}"""))
        for (frame in incoming) {
          val command = Json.parseToJsonElement((frame as Frame.Text).readText()).jsonObject
          val type = command.getValue("type").jsonPrimitive.content
          val id = command.getValue("id").jsonPrimitive.int
          sent += type
          // The session's own state subscription, as a real Home Assistant answers it.
          val reply =
            when (type) {
              "subscribe_events" -> ok(id, "null")
              "get_states" -> ok(id, "[]")
              else -> answer(type, id)
            }
          send(Frame.Text(reply))
        }
      }
    }
  }

  private fun ApplicationTestBuilder.session() =
    LiveHaSession("http://localhost", "token", createClient {}.engine)

  private fun List<String>.terrazzo() = filter {
    it.startsWith("terrazzo/") || it == "manifest/get"
  }

  @Test
  fun capabilitiesLeftOutAreAnsweredWithoutAskingAndInfoIsAskedOnce() = testApplication {
    val sent = CopyOnWriteArrayList<String>()
    fakeHa(sent) { type, id ->
      when (type) {
        // A non-administrator with no push and no agent: only selections and the design server.
        "terrazzo/info" ->
          ok(
            id,
            """{"protocol":1,"min_client":1,"version":"0.1.99","capabilities":
              {"design_selections":1,"design_server":{"available":false}}}""",
          )
        else -> unknown(id)
      }
    }
    val session = session()
    try {
      session.connect()
      assertIs<TerrazzoIntegration.Installed>(session.terrazzoIntegration())
      assertNull(session.registerSilentPush("hook", "fcm", "https://relay.test"))
      assertNull(session.watchStates("hook", listOf("light.kitchen"), 60))
      assertEquals(emptyList(), session.takeHeldNotifications("hook"))
      assertEquals(BuiltInAgent.Off, session.builtInAgent())
      assertNull(session.privateDesignLink("/designs"))
      // One terrazzo/info for the connection; none of the left-out commands were sent.
      assertEquals(listOf("terrazzo/info"), sent.terrazzo())
    } finally {
      session.close()
    }
  }

  @Test
  fun anOfferedCapabilityStillSendsItsCommand() = testApplication {
    val sent = CopyOnWriteArrayList<String>()
    fakeHa(sent) { type, id ->
      when (type) {
        "terrazzo/info" ->
          ok(id, """{"protocol":1,"min_client":1,"version":"0.1.99","capabilities":{"push":1}}""")
        "terrazzo/push/register" ->
          ok(id, """{"push_url":"https://ha.test/api/terrazzo/push/hook","push_token":"t"}""")
        else -> unknown(id)
      }
    }
    val session = session()
    try {
      session.connect()
      assertEquals(
        "https://ha.test/api/terrazzo/push/hook" to "t",
        session.registerSilentPush("hook", "fcm", "https://relay.test"),
      )
      assertEquals(listOf("terrazzo/info", "terrazzo/push/register"), sent.terrazzo())
    } finally {
      session.close()
    }
  }

  @Test
  fun anIntegrationOlderThanTheHandshakeIsTriedCommandByCommand() = testApplication {
    val sent = CopyOnWriteArrayList<String>()
    fakeHa(sent) { type, id ->
      when (type) {
        "terrazzo/info" -> unknown(id)
        "manifest/get" -> ok(id, """{"domain":"terrazzo","version":"0.1.90"}""")
        "terrazzo/push/register" ->
          ok(id, """{"push_url":"https://ha.test/api/terrazzo/push/hook","push_token":"t"}""")
        else -> unknown(id)
      }
    }
    val session = session()
    try {
      session.connect()
      assertEquals(TerrazzoIntegration.TooOld("0.1.90"), session.terrazzoIntegration())
      assertEquals(
        "https://ha.test/api/terrazzo/push/hook" to "t",
        session.registerSilentPush("hook", "fcm", "https://relay.test"),
      )
      assertEquals(
        listOf("terrazzo/info", "manifest/get", "terrazzo/push/register"),
        sent.terrazzo(),
      )
    } finally {
      session.close()
    }
  }

  @Test
  fun withoutTheIntegrationNoTerrazzoCommandIsSent() = testApplication {
    val sent = CopyOnWriteArrayList<String>()
    fakeHa(sent) { type, id ->
      if (type == "manifest/get")
        """{"id":$id,"type":"result","success":false,"error":{"code":"not_found","message":"No."}}"""
      else unknown(id)
    }
    val session = session()
    try {
      session.connect()
      assertEquals(TerrazzoIntegration.Absent, session.terrazzoIntegration())
      assertNull(session.registerSilentPush("hook", "fcm", "https://relay.test"))
      assertEquals(false, session.privateDesignServer())
      assertNull(session.sharedDesignSelections())
      assertEquals(listOf("terrazzo/info", "manifest/get"), sent.terrazzo())
    } finally {
      session.close()
    }
  }

  @Test
  fun aCachedSessionForwardsEveryTerrazzoCommand() = testApplication {
    val sent = CopyOnWriteArrayList<String>()
    fakeHa(sent) { type, id ->
      when (type) {
        "terrazzo/info" ->
          ok(
            id,
            """{"protocol":1,"min_client":1,"version":"0.1.99","capabilities":
              {"design_selections":1,"design_server":{"available":true}}}""",
          )
        "terrazzo/design_selections/get" -> ok(id, """{"cards":{"card-1":"design-1"}}""")
        "terrazzo/design_selections/set" -> ok(id, "{}")
        else -> unknown(id)
      }
    }
    val live = session()
    val cached = CachedHaSession(live, OfflineCacheStorage(createTempDirectory("cache").toFile()))
    try {
      live.connect()
      val terrazzo = assertNotNull(cached.terrazzo)
      assertEquals(mapOf("card-1" to "design-1"), terrazzo.sharedDesignSelections())
      terrazzo.setSharedDesignSelection("card-1", "design-2")
      assertEquals(
        listOf("terrazzo/info", "terrazzo/design_selections/get", "terrazzo/design_selections/set"),
        sent.terrazzo(),
      )
    } finally {
      live.close()
    }
  }

  @Test
  fun aSessionWithNoHomeAssistantBehindItHasNoTerrazzoCommands() = runTest {
    val store = OfflineCacheStorage(createTempDirectory("cache").toFile())
    assertNull(DemoHaSession().terrazzo)
    // Cached around nothing live: it can be asked, and answers as without the integration.
    val cacheOnly = assertNotNull(CachedHaSession.cacheOnly("http://ha.test", store).terrazzo)
    assertNull(cacheOnly.terrazzoIntegration())
    assertNull(cacheOnly.sharedDesignSelections())
    assertEquals(BuiltInAgent.Off, cacheOnly.builtInAgent())
  }
}
