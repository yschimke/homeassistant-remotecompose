package ee.schimke.ha.designserver

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

class AgentRelayTest {
  private fun relay(clock: () -> Long = System::currentTimeMillis, wait: Long = 2_000) =
    AgentRelay(AgentDesignSessions(clock), DashboardAgentSessions(clock), clock, wait)

  private val baseline = Json.parseToJsonElement("""{"views":[{"title":"Home"}]}""").jsonObject
  private val changed = Json.parseToJsonElement("""{"views":[{"title":"Garden"}]}""").jsonObject

  private suspend fun ApplicationTestBuilder.create(): JsonObject {
    val response =
      client.post("/api/studio/dashboard-sessions") {
        setBody(
          buildJsonObject {
            put("request", "Tidy the security view")
            put("dashboard", baseline)
          }
            .toString()
        )
      }
    assertEquals(HttpStatusCode.OK, response.status)
    return Json.parseToJsonElement(response.bodyAsText()).jsonObject
  }

  private suspend fun ApplicationTestBuilder.mcp(
    tool: String,
    args: JsonObject,
    origin: String? = null,
  ): JsonObject {
    val response =
      client.post("/api/agent/mcp") {
        origin?.let { header(HttpHeaders.Origin, it) }
        setBody(
          buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "tools/call")
            putJsonObject("params") {
              put("name", tool)
              put("arguments", args)
            }
          }
            .toString()
        )
      }
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("result").jsonObject
  }

  private fun JsonObject.data() = getValue("structuredContent").jsonObject

  private suspend fun ApplicationTestBuilder.ownerState(
    owner: String,
    since: Long? = null,
  ): JsonObject {
    val response =
      client.get("/api/agent/session" + (since?.let { "?since=$it" } ?: "")) {
        header(HttpHeaders.Authorization, "Bearer $owner")
      }
    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals("*", response.headers[HttpHeaders.AccessControlAllowOrigin])
    return Json.parseToJsonElement(response.bodyAsText()).jsonObject
  }

  @Test
  fun joinCodeConnectsAgentAndConversationFlowsBothWays() = testApplication {
    val relay = relay()
    application {
      routing {
        dashboardAgentRoutes(relay)
        agentRelayRoutes(relay)
      }
    }
    val access = create()
    val owner = access.getValue("ownerToken").jsonPrimitive.content
    val code = access.getValue("joinCode").jsonPrimitive.content
    assertTrue(Regex("[A-Z2-9]{4}-[A-Z2-9]{4}").matches(code), code)

    val first = ownerState(owner)
    assertEquals(false, first.getValue("agent").jsonObject.getValue("joined").jsonPrimitive.boolean)
    assertEquals(code, first.getValue("joinCode").jsonPrimitive.content)

    // A browser page can't drive the agent endpoint.
    assertEquals(
      HttpStatusCode.Forbidden,
      client
        .post("/api/agent/mcp") {
          header(HttpHeaders.Origin, "https://evil.example")
          setBody("{}")
        }
        .status,
    )
    // A wrong code is refused; the right one is single use and lower case is fine.
    assertEquals(
      true,
      mcp("join_session", buildJsonObject { put("code", "AAAA-AAAA") })["isError"]
        ?.jsonPrimitive
        ?.boolean,
    )
    val joined =
      mcp(
          "join_session",
          buildJsonObject {
            put("code", code.lowercase())
            put("agentName", "Claude")
          },
        )
        .data()
    val session = joined.getValue("session").jsonPrimitive.content
    assertEquals("dashboard", joined.getValue("kind").jsonPrimitive.content)
    assertEquals(
      true,
      mcp("join_session", buildJsonObject { put("code", code) })["isError"]?.jsonPrimitive?.boolean,
    )

    val joinedState = ownerState(owner)
    val agent = joinedState.getValue("agent").jsonObject
    assertEquals("Claude", agent.getValue("name").jsonPrimitive.content)
    assertEquals(true, agent.getValue("connected").jsonPrimitive.boolean)
    assertNull(joinedState["joinCode"], "the code is spent")

    val read = mcp("get_session", buildJsonObject { put("session", session) }).data()
    assertEquals(baseline, read["dashboard"])
    assertEquals(0, read.getValue("revision").jsonPrimitive.int)

    // The person's long-poll wakes on the draft rather than on a timer.
    coroutineScope {
      val version = ownerState(owner).getValue("version").jsonPrimitive.long
      val waiting = async { ownerState(owner, since = version) }
      delay(100)
      val submitted =
        mcp(
          "submit_draft",
          buildJsonObject {
            put("session", session)
            put("revision", 0)
            put("dashboard", changed)
            put("summary", "Garden view")
          },
        )
      assertNull(submitted["isError"], submitted.toString())
      var woke = waiting.await()
      assertTrue(woke.getValue("version").jsonPrimitive.long > version)
      // Presence ("Submitting a draft") can wake the poll first; like the app, poll on from the
      // version it returned until the draft's revision arrives.
      repeat(5) {
        if (woke.getValue("revision").jsonPrimitive.int == 1) return@repeat
        woke = ownerState(owner, since = woke.getValue("version").jsonPrimitive.long)
      }
      assertEquals(1, woke.getValue("revision").jsonPrimitive.int)
    }
    val draft =
      client.get("/api/studio/dashboard-session") {
        header(HttpHeaders.Authorization, "Bearer $owner")
      }
    assertEquals(changed, Json.parseToJsonElement(draft.bodyAsText()).jsonObject["dashboard"])

    // The agent waits for feedback; a comment pinned to a card ends the wait.
    coroutineScope {
      val feedback = async {
        mcp(
            "await_feedback",
            buildJsonObject {
              put("session", session)
              put("since", 0)
            },
          )
          .data()
      }
      delay(100)
      val comment =
        client.post("/api/agent/session/comment") {
          header(HttpHeaders.Authorization, "Bearer $owner")
          setBody("""{"text":"Make the camera bigger","target":"Front door camera"}""")
        }
      assertEquals(HttpStatusCode.OK, comment.status)
      val got = feedback.await()
      val first = got.getValue("feedback").jsonArray.single().jsonObject
      assertEquals("Make the camera bigger", first.getValue("text").jsonPrimitive.content)
      assertEquals("Front door camera", first.getValue("target").jsonPrimitive.content)
    }
    // Legacy readers of the dashboard request still see comments as notes.
    assertTrue(
      mcp("get_session", buildJsonObject { put("session", session) })
        .data()
        .toString()
        .contains("Make the camera bigger")
    )

    mcp(
      "send_message",
      buildJsonObject {
        put("session", session)
        put("text", "Should the lock stay at the top?")
      },
    )
    val messages = ownerState(owner).getValue("messages").jsonArray.map { it.jsonObject }
    assertEquals(
      listOf("agent:summary", "person:comment", "agent:message"),
      messages.map {
        "${it["from"]!!.jsonPrimitive.content}:${it["kind"]!!.jsonPrimitive.content}"
      },
    )

    val decided =
      client.post("/api/agent/session/decision") {
        header(HttpHeaders.Authorization, "Bearer $owner")
        setBody("""{"decision":"accepted"}""")
      }
    assertEquals(HttpStatusCode.OK, decided.status)
    val end =
      mcp(
          "await_feedback",
          buildJsonObject {
            put("session", session)
            put("since", 2)
          },
        )
        .data()
    assertEquals("accepted", end.getValue("decision").jsonPrimitive.content)

    // Revoking ends the session for both sides.
    assertEquals(
      HttpStatusCode.OK,
      client
        .delete("/api/agent/session") { header(HttpHeaders.Authorization, "Bearer $owner") }
        .status,
    )
    assertEquals(
      HttpStatusCode.NotFound,
      client
        .get("/api/agent/session") { header(HttpHeaders.Authorization, "Bearer $owner") }
        .status,
    )
    assertEquals(
      true,
      mcp("get_session", buildJsonObject { put("session", session) })["isError"]
        ?.jsonPrimitive
        ?.boolean,
    )
  }

  @Test
  fun agentCanAskFirstAndThePersonEntersItsCode() = testApplication {
    val relay = relay()
    application {
      routing {
        dashboardAgentRoutes(relay)
        agentRelayRoutes(relay)
      }
    }
    val owner = create().getValue("ownerToken").jsonPrimitive.content
    val asked = mcp("request_access", buildJsonObject { put("agentName", "Codex") }).data()
    val code = asked.getValue("code").jsonPrimitive.content
    val poll = asked.getValue("requestToken").jsonPrimitive.content
    // A token that is neither a session nor a request is unknown, not an open door.
    assertEquals(
      true,
      mcp("poll_access", buildJsonObject { put("requestToken", "nope") })["isError"]
        ?.jsonPrimitive
        ?.boolean,
    )

    coroutineScope {
      val polling = async {
        mcp("poll_access", buildJsonObject { put("requestToken", poll) }).data()
      }
      delay(100)
      val claim =
        client.post("/api/agent/claim") {
          header(HttpHeaders.Authorization, "Bearer $owner")
          setBody(buildJsonObject { put("code", code) }.toString())
        }
      assertEquals(HttpStatusCode.OK, claim.status)
      val approved = polling.await()
      assertEquals("joined", approved.getValue("status").jsonPrimitive.content)
      val session = approved.getValue("session").jsonPrimitive.content
      assertEquals(
        "Tidy the security view",
        mcp("get_session", buildJsonObject { put("session", session) })
          .data()
          .getValue("request")
          .jsonPrimitive
          .content,
      )
    }
    assertEquals(
      "Codex",
      ownerState(owner).getValue("agent").jsonObject.getValue("name").jsonPrimitive.content,
    )
    // The code was used up.
    assertEquals(
      HttpStatusCode.NotFound,
      client
        .post("/api/agent/claim") {
          header(HttpHeaders.Authorization, "Bearer $owner")
          setBody(buildJsonObject { put("code", code) }.toString())
        }
        .status,
    )
  }

  @Test
  fun idleSessionsSlideButNeverPastADay() {
    var now = 0L
    val relay = relay({ now })
    val access = relay.dashboards.create("Tidy", baseline)
    relay.register(AgentRelay.Kind.Dashboard, access.owner, access.agent, "Tidy")
    // Active every 50 minutes: still open after 23 hours.
    repeat(28) {
      now += 50 * 60_000L
      assertNotNull(relay.seen(access.agent), "at ${now / 60_000} min")
    }
    now = 24 * 60 * 60_000L + 1
    assertNull(relay.seen(access.agent))
    assertFalse(relay.isOwner(access.owner))
  }

  @Test
  fun wrongCodesAreThrottledForEveryone() {
    val relay = relay()
    val access = relay.dashboards.create("Tidy", baseline)
    val code = relay.register(AgentRelay.Kind.Dashboard, access.owner, access.agent, "Tidy")
    repeat(AgentRelay.MAX_FAILURES_PER_MINUTE) { relay.join("ZZZZ-ZZZZ", null) }
    assertNull(relay.join(code, null), "a guessing burst blocks even a right code for a minute")
  }

  @Test
  fun cardSessionJoinsAndOwnerSeesDraftRevision() = testApplication {
    val relay = relay()
    application { routing { agentRelayRoutes(relay) } }
    val document = buildJsonObject { put("id", "card-x") }
    val access = relay.cards.create("Bigger icon", document)
    val code = relay.register(AgentRelay.Kind.Card, access.owner, access.agent, "Bigger icon")
    val joined = mcp("join_session", buildJsonObject { put("code", code) }).data()
    assertEquals("card", joined.getValue("kind").jsonPrimitive.content)
    val read =
      mcp(
          "get_session",
          buildJsonObject { put("session", joined.getValue("session").jsonPrimitive.content) },
        )
        .data()
    assertEquals(document, read["document"])
    assertEquals("Bigger icon", read.getValue("instruction").jsonPrimitive.content)
    assertTrue(read.containsKey("sharedValues"))
    assertEquals("card", ownerState(access.owner).getValue("kind").jsonPrimitive.content)
  }
}
