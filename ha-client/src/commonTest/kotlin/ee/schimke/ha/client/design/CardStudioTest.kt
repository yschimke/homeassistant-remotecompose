package ee.schimke.ha.client.design

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class CardStudioTest {
  private val document =
    Json.parseToJsonElement(
        """{
    "schema":"compose-ui-builder-document/v1-candidate","id":"card-test",
    "catalogPin":{"systemId":"ha-cards"},"stateVariables":{"sensor.s0.state":{"type":"text","initialValue":"21 °C"}},
    "roots":["root"],"nodes":{
      "root":{"id":"root","componentId":"ha-cards/dashboard-card","properties":{},"slots":{"content":["text"]}},
      "text":{"id":"text","componentId":"ha-cards/remote-text","properties":{"text":{"type":"state","variable":"sensor.s0.state"}},"slots":{}}
    }}"""
      )
      .jsonObject

  @Test
  fun previewIncludesOnlySelectedEntitiesAndPresentationAttributes() {
    val card =
      CardConfig(
        "gauge",
        Json.parseToJsonElement(
            """{"type":"gauge","entity":"sensor.private_id","max":100,"image":"https://camera.test/?token=private","access_token":"secret"}"""
          )
          .jsonObject,
      )
    val states =
      HaSnapshot(
        states =
          mapOf(
            "sensor.private_id" to
              EntityState(
                "sensor.private_id",
                "21",
                buildJsonObject {
                  put("friendly_name", "Kitchen")
                  put("unit_of_measurement", "°C")
                  put("entity_picture", "https://camera.test/?token=private")
                  put("access_token", "secret")
                },
              ),
            "person.owner" to EntityState("person.owner", "home"),
          )
      )
    val preview = cardPreviewData(card, states)
    assertEquals("sensor.s0", preview["card"]!!.jsonObject["entity"]!!.jsonPrimitive.content)
    assertEquals(setOf("sensor.s0"), preview["states"]!!.jsonObject.keys)
    assertTrue(preview.toString().contains("Kitchen"))
    for (private in listOf("private_id", "person.owner", "camera.test", "secret")) assertFalse(
      preview.toString().contains(private)
    )
  }

  @Test
  fun providersUseTheirOwnAuthenticationAndResponseEnvelope() = runTest {
    for (provider in CardAgentProvider.entries) {
      val engine = MockEngine { request ->
        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
        assertEquals("chosen-model", body["model"]!!.jsonPrimitive.content)
        if (provider == CardAgentProvider.Claude) {
          assertEquals("key", request.headers["x-api-key"])
          assertNull(request.headers["Authorization"])
          assertEquals("2023-06-01", request.headers["anthropic-version"])
          assertNotNull(body["system"])
          respond(
            buildJsonObject {
              put("stop_reason", "end_turn")
              putJsonArray("content") {
                add(
                  buildJsonObject {
                    put("type", "text")
                    put("text", document.toString())
                  }
                )
              }
            }
              .toString()
          )
        } else {
          assertEquals("Bearer key", request.headers["Authorization"])
          assertNull(request.headers["x-api-key"])
          if (provider == CardAgentProvider.Agent) assertEquals("agent.test", request.url.host)
          respond(
            buildJsonObject {
              putJsonArray("choices") {
                add(
                  buildJsonObject {
                    put("finish_reason", "stop")
                    putJsonObject("message") { put("content", "```json\n$document\n```") }
                  }
                )
              }
            }
              .toString()
          )
        }
      }
      val agent = AgentCardDesigner(engine)
      try {
        assertEquals(
          document,
          agent.design(
            provider,
            "key",
            "chosen-model",
            "Improve readability",
            document,
            "https://agent.test/v1/chat/completions",
          ),
        )
      } finally {
        agent.close()
      }
    }
  }

  @Test
  fun providerErrorsNeverExposeKeysOrResponseBodies() = runTest {
    val agent =
      AgentCardDesigner(
        MockEngine { respond("secret-key detailed provider response", HttpStatusCode.Unauthorized) }
      )
    try {
      val error =
        assertFailsWith<IllegalStateException> {
          agent.design(CardAgentProvider.OpenAI, "secret-key", "model", "fix", document)
        }
      assertFalse(error.message.orEmpty().contains("secret-key"))
      assertTrue(error.message.orEmpty().contains("401"))
    } finally {
      agent.close()
    }
  }

  @Test
  fun draftGateRejectsContractChangesUnknownBindingsAndCycles() {
    assertFailsWith<IllegalArgumentException> {
      validateCardDraft(
        document,
        JsonObject(document + ("catalogPin" to buildJsonObject { put("systemId", "other") })),
      )
    }
    val unknown =
      Json.parseToJsonElement(
          document
            .toString()
            .replace("\"variable\":\"sensor.s0.state\"", "\"variable\":\"person.owner\"")
        )
        .jsonObject
    assertFailsWith<IllegalArgumentException> { validateCardDraft(document, unknown) }
    val cycle =
      Json.parseToJsonElement(
          document.toString().replace("\"content\":[\"text\"]", "\"content\":[\"root\"]")
        )
        .jsonObject
    assertFailsWith<IllegalArgumentException> { validateCardDraft(document, cycle) }
  }

  @Test
  fun draftGateRejectsReplacingLiveValuesWithFrozenPreviewText() {
    val frozen =
      Json.parseToJsonElement(
          document
            .toString()
            .replace(
              "\"type\":\"state\",\"variable\":\"sensor.s0.state\"",
              "\"type\":\"string\",\"value\":\"21 °C\"",
            )
        )
        .jsonObject
    assertFailsWith<IllegalArgumentException> { validateCardDraft(document, frozen) }
  }

  @Test
  fun serverSettingsAcceptLibraryUrlAndRejectCredentialsOrInsecureUrls() {
    assertEquals("https://designs.test", studioServerUrl("https://designs.test/designs/v1/"))
    for (url in
      listOf(
        "http://designs.test",
        "https://key@designs.test",
        "https://designs.test/?token=key",
        "https://designs.test/#key",
      )) assertFailsWith<IllegalArgumentException> { studioServerUrl(url) }
  }
}
