package ee.schimke.ha.client

import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Long-lived access tokens are made with `auth/long_lived_access_token` and deleted by name: only
 * long-lived tokens whose name matches go, never the user's sign-ins.
 */
class HaClientLongLivedTokenTest {

  private fun fakeHa(
    commands: MutableList<JsonObject>,
    answer: (JsonObject) -> String,
    block: suspend (HaClient) -> Unit,
  ) = testApplication {
    install(WebSockets)
    routing {
      webSocket("/api/websocket") {
        send(Frame.Text("""{"type":"auth_required"}"""))
        incoming.receive()
        send(Frame.Text("""{"type":"auth_ok"}"""))
        for (frame in incoming) {
          val command = Json.parseToJsonElement((frame as Frame.Text).readText()).jsonObject
          commands += command
          val id = command.getValue("id").jsonPrimitive.int
          send(Frame.Text("""{"id":$id,"type":"result",${answer(command)}}"""))
        }
      }
    }
    val client = HaClient(HaConfig("http://localhost", "token"), createClient {}.engine)
    try {
      client.connect()
      block(client)
    } finally {
      client.close()
    }
  }

  private val JsonObject.type
    get() = getValue("type").jsonPrimitive.content

  @Test
  fun aTokenIsMadeWithItsNameAndLifespan() {
    val commands = mutableListOf<JsonObject>()
    fakeHa(commands, { """"success":true,"result":"llat-secret"""" }) { client ->
      assertEquals("llat-secret", client.createLongLivedAccessToken("Terrazzo Watch", 365))
    }
    val command = commands.single()
    assertEquals("auth/long_lived_access_token", command.type)
    assertEquals("Terrazzo Watch", command.getValue("client_name").jsonPrimitive.content)
    assertEquals(365, command.getValue("lifespan").jsonPrimitive.int)
  }

  @Test
  fun aRefusedTokenFails() {
    fakeHa(
      mutableListOf(),
      { """"success":false,"error":{"code":"invalid_format","message":"exists"}""" },
    ) { client ->
      assertFailsWith<IllegalStateException> {
        client.createLongLivedAccessToken("Terrazzo Watch", 365)
      }
    }
  }

  @Test
  fun onlyLongLivedTokensWithAMatchingNameAreDeleted() {
    val commands = mutableListOf<JsonObject>()
    val tokens =
      """[
        {"id":"watch1","client_name":"Terrazzo Watch · Pixel Watch","type":"long_lived_access_token"},
        {"id":"other","client_name":"Grafana","type":"long_lived_access_token"},
        {"id":"signin","client_name":"Terrazzo Watch · Pixel Watch","type":"normal"},
        {"id":"app","client_name":null,"type":"normal"}
      ]"""
    fakeHa(
      commands,
      { command ->
        if (command.type == "auth/refresh_tokens") """"success":true,"result":$tokens"""
        else """"success":true,"result":null"""
      },
    ) { client ->
      assertEquals(1, client.deleteLongLivedAccessTokens { it.startsWith("Terrazzo Watch") })
    }
    assertEquals(
      listOf("auth/refresh_tokens", "auth/delete_refresh_token"),
      commands.map { it.type },
    )
    assertEquals("watch1", commands.last().getValue("refresh_token_id").jsonPrimitive.content)
  }
}
