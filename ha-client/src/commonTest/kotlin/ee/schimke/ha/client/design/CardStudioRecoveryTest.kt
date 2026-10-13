package ee.schimke.ha.client.design

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class CardStudioRecoveryTest {
  private val document =
    Json.parseToJsonElement(
        """{
    "schema":"compose-ui-builder-document/v1-candidate","id":"card-old","title":"Original",
    "catalogPin":{"systemId":"ha-cards"},"stateVariables":{"sensor.s0.state":{"type":"text","valueType":"string","initialValue":"21 °C"}},
    "roots":["root"],"nodes":{
      "root":{"id":"root","componentId":"ha-cards/dashboard-card","properties":{},"slots":{"content":["text"]}},
      "text":{"id":"text","componentId":"ha-cards/remote-text","properties":{"text":{"type":"state","variable":"sensor.s0.state"}},"slots":{}}
    }}"""
      )
      .jsonObject
  private val edited = JsonObject(document + ("title" to JsonPrimitive("My edited design")))
  private val fresh =
    Json.parseToJsonElement(
        document.toString().replace("card-old", "card-new").replace("21 °C", "22 °C")
      )
      .jsonObject
  private val card =
    CardConfig(
      "gauge",
      buildJsonObject {
        put("type", "gauge")
        put("entity", "sensor.temperature")
      },
    )
  private val snapshot =
    HaSnapshot(states = mapOf("sensor.temperature" to EntityState("sensor.temperature", "22")))
  private val link = "/studio/" + "a".repeat(32)

  @Test
  fun expiredDraftAutomaticallyRefreshesCurrentValuesAndKeepsEdits() = runTest {
    val paths = mutableListOf<String>()
    val engine = MockEngine { request ->
      paths += request.url.encodedPath
      when (paths.size) {
        1 -> respond("expired private document", HttpStatusCode.Gone)
        2 -> {
          val sent = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
          assertEquals(
            "22",
            sent["states"]!!.jsonObject["sensor.s0"]!!.jsonObject["state"]!!.jsonPrimitive.content,
          )
          respond(fresh.toString())
        }
        else -> {
          val sent = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
          assertEquals("card-new", sent["id"]!!.jsonPrimitive.content)
          assertEquals("My edited design", sent["title"]!!.jsonPrimitive.content)
          assertEquals(fresh["stateVariables"], sent["stateVariables"])
          respond(buildJsonObject { put("path", link) }.toString())
        }
      }
    }
    val studio = CardStudioClient("https://designs.test/designs/v1", engine)
    try {
      val opened = studio.openDraft(edited, card, snapshot)
      assertEquals("https://designs.test$link", opened.url)
      assertEquals(edited["nodes"], opened.document["nodes"])
      assertEquals(
        listOf("/api/studio/handoffs", "/api/studio/seed", "/api/studio/handoffs"),
        paths,
      )
    } finally {
      studio.close()
    }
  }

  @Test
  fun repeatedExpiryStopsAfterOneRefresh() = runTest {
    var calls = 0
    val studio =
      CardStudioClient(
        "https://designs.test",
        MockEngine {
          calls++
          if (it.url.encodedPath.endsWith("seed")) respond(fresh.toString())
          else respond("expired", HttpStatusCode.Gone)
        },
      )
    try {
      assertEquals(
        410,
        assertFailsWith<CardStudioException> { studio.openDraft(edited, card, snapshot) }.status,
      )
      assertEquals(3, calls)
    } finally {
      studio.close()
    }
  }

  @Test
  fun serverErrorsAreActionableWithoutLeakingBodiesOrRetrying() = runTest {
    for (status in
      listOf(
        HttpStatusCode.NotFound,
        HttpStatusCode.TooManyRequests,
        HttpStatusCode.ServiceUnavailable,
        HttpStatusCode.Forbidden,
      )) {
      var calls = 0
      val studio =
        CardStudioClient(
          "https://designs.test",
          MockEngine {
            calls++
            respond("private data and operator token", status)
          },
        )
      try {
        val failure =
          assertFailsWith<CardStudioException> { studio.openDraft(edited, card, snapshot) }
        assertEquals(status.value, failure.status)
        assertFalse(failure.message.orEmpty().contains("private"))
        assertFalse(failure.message.orEmpty().contains("operator"))
        assertEquals(1, calls)
      } finally {
        studio.close()
      }
    }
  }

  @Test
  fun refreshRejectsChangedCatalogOrBindingTypeInsteadOfDroppingEdits() {
    val changedType =
      Json.parseToJsonElement(
          fresh.toString().replace("\"valueType\":\"string\"", "\"valueType\":\"bool\"")
        )
        .jsonObject
    assertFailsWith<IllegalArgumentException> { refreshCardDraft(edited, changedType) }
    val changedCatalog =
      JsonObject(fresh + ("catalogPin" to buildJsonObject { put("systemId", "other") }))
    assertFailsWith<IllegalArgumentException> { refreshCardDraft(edited, changedCatalog) }
  }

  @Test
  fun handoffCannotRedirectTheAppToAnotherOriginOrCarryCredentials() = runTest {
    for (path in
      listOf("https://evil.test/", "//evil.test/", "$link?token=private", "$link/../edit")) {
      val studio =
        CardStudioClient(
          "https://designs.test",
          MockEngine { respond(buildJsonObject { put("path", path) }.toString()) },
        )
      try {
        assertFailsWith<IllegalArgumentException> { studio.handoff(document) }
      } finally {
        studio.close()
      }
    }
  }

  @Test
  fun cancellationStopsTheProviderRequest() = runTest {
    val started = CompletableDeferred<Unit>()
    val stopped = CompletableDeferred<Unit>()
    val agent =
      AgentCardDesigner(
        MockEngine {
          started.complete(Unit)
          try {
            awaitCancellation()
          } finally {
            stopped.complete(Unit)
          }
        }
      )
    try {
      val request = async {
        agent.design(CardAgentProvider.OpenAI, "key", "model", "fix", document)
      }
      started.await()
      request.cancelAndJoin()
      stopped.await()
      assertTrue(request.isCancelled)
    } finally {
      agent.close()
    }
  }

  @Test
  fun truncatedAndToolCallingProviderRepliesCannotBecomeDrafts() = runTest {
    for ((provider, reason) in
      listOf(
        CardAgentProvider.OpenAI to "length",
        CardAgentProvider.OpenAI to "tool_calls",
        CardAgentProvider.Claude to "max_tokens",
        CardAgentProvider.Claude to "tool_use",
      )) {
      val envelope =
        if (provider == CardAgentProvider.Claude)
          buildJsonObject {
            put("stop_reason", reason)
            putJsonArray("content") {
              add(
                buildJsonObject {
                  put("type", "text")
                  put("text", document.toString())
                }
              )
            }
          }
        else
          buildJsonObject {
            putJsonArray("choices") {
              add(
                buildJsonObject {
                  put("finish_reason", reason)
                  putJsonObject("message") { put("content", document.toString()) }
                }
              )
            }
          }
      val agent = AgentCardDesigner(MockEngine { respond(envelope.toString()) })
      try {
        assertFailsWith<IllegalStateException> {
          agent.design(provider, "key", "model", "fix", document)
        }
      } finally {
        agent.close()
      }
    }
  }

  @Test
  fun malformedProviderEnvelopeAndDocumentDoNotEchoPrivateText() = runTest {
    for (body in
      listOf(
        "private-preview-not-json",
        buildJsonObject {
          putJsonArray("choices") {
            add(
              buildJsonObject {
                put("finish_reason", "stop")
                putJsonObject("message") { put("content", "private-preview-not-json") }
              }
            )
          }
        }
          .toString(),
      )) {
      val agent = AgentCardDesigner(MockEngine { respond(body) })
      try {
        val failure =
          assertFailsWith<IllegalArgumentException> {
            agent.design(CardAgentProvider.OpenAI, "key", "model", "fix", document)
          }
        assertFalse(failure.message.orEmpty().contains("private-preview"))
      } finally {
        agent.close()
      }
    }
  }

  @Test
  fun invalidInputsFailBeforeMakingAProviderRequest() = runTest {
    var calls = 0
    val agent =
      AgentCardDesigner(
        MockEngine {
          calls++
          error("Unexpected request")
        }
      )
    try {
      assertFailsWith<IllegalArgumentException> {
        agent.design(CardAgentProvider.OpenAI, "", "model", "fix", document)
      }
      assertFailsWith<IllegalArgumentException> {
        agent.design(CardAgentProvider.OpenAI, "key", "model", "x".repeat(8_001), document)
      }
      assertFailsWith<IllegalArgumentException> {
        agent.design(CardAgentProvider.Agent, "key", "model", "fix", document, "http://agent.test")
      }
      assertEquals(0, calls)
    } finally {
      agent.close()
    }
  }
}
