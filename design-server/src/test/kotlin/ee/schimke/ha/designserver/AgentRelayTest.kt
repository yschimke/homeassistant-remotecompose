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
import kotlinx.coroutines.runBlocking
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
  fun commentsBeforeAnAgentJoinsAreItsFirstWork() = testApplication {
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
    // The person comments on a card while no agent has joined yet.
    val early =
      client.post("/api/agent/session/comment") {
        header(HttpHeaders.Authorization, "Bearer $owner")
        setBody("""{"text":"Make the camera bigger","target":"Front door camera"}""")
      }
    assertEquals(HttpStatusCode.OK, early.status)
    val session =
      mcp("join_session", buildJsonObject { put("code", code) })
        .data()
        .getValue("session")
        .jsonPrimitive
        .content
    val read = mcp("get_session", buildJsonObject { put("session", session) }).data()
    val comment = read.getValue("conversation").jsonArray.single().jsonObject
    assertEquals("Make the camera bigger", comment.getValue("text").jsonPrimitive.content)
    assertEquals("Front door camera", comment.getValue("target").jsonPrimitive.content)
    assertTrue(
      read.getValue("next").jsonPrimitive.content.contains("already commented"),
      "the agent is told to start with it",
    )
    // Once it has answered, a new session read just waits for what comes next.
    mcp(
      "send_message",
      buildJsonObject {
        put("session", session)
        put("text", "Bigger it is")
      },
    )
    val after = mcp("get_session", buildJsonObject { put("session", session) }).data()
    assertTrue(after.getValue("next").jsonPrimitive.content.startsWith("Say hello"))
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
  fun connectOnceThenRequestsReachTheAgentWithoutACode() = testApplication {
    val relay = relay()
    application {
      routing {
        dashboardAgentRoutes(relay)
        agentRelayRoutes(relay)
      }
    }
    // Setup: the person's Settings make a link and show its code; the agent connects with it.
    val created = Json.parseToJsonElement(client.post("/api/agent/links").bodyAsText()).jsonObject
    val ownerLink = created.getValue("link").jsonPrimitive.content
    val setupCode = created.getValue("code").jsonPrimitive.content
    suspend fun linkState() =
      Json.parseToJsonElement(
          client
            .get("/api/agent/link") { header(HttpHeaders.Authorization, "Bearer $ownerLink") }
            .bodyAsText()
        )
        .jsonObject
    assertEquals(false, linkState().getValue("connected").jsonPrimitive.boolean)
    val connected =
      mcp(
          "connect",
          buildJsonObject {
            put("code", setupCode)
            put("agentName", "Claude")
          },
        )
        .data()
    val agentLink = connected.getValue("link").jsonPrimitive.content
    val afterSetup = linkState()
    assertEquals("Claude", afterSetup.getValue("name").jsonPrimitive.content)
    assertEquals(true, afterSetup.getValue("listening").jsonPrimitive.boolean)
    assertNull(afterSetup["code"], "the setup code is spent")

    // Every request after that goes straight to the agent: no code, no prompt.
    coroutineScope {
      val listening = async {
        mcp("await_request", buildJsonObject { put("link", agentLink) }).data()
      }
      delay(100)
      val started =
        client.post("/api/studio/dashboard-sessions") {
          setBody(
            buildJsonObject {
              put("request", "Tidy the security view")
              put("dashboard", baseline)
              put("agentLink", ownerLink)
            }
              .toString()
          )
        }
      val access = Json.parseToJsonElement(started.bodyAsText()).jsonObject
      assertNull(access["joinCode"], "a linked request needs no code")
      assertEquals(true, access["linked"]?.jsonPrimitive?.boolean)
      val owner = access.getValue("ownerToken").jsonPrimitive.content
      val state = ownerState(owner)
      assertEquals(true, state["linked"]?.jsonPrimitive?.boolean)
      assertEquals(
        "Claude",
        state.getValue("agent").jsonObject.getValue("name").jsonPrimitive.content,
      )
      assertNull(state["joinCode"])

      val inbox = listening.await()
      val request = inbox.getValue("requests").jsonArray.single().jsonObject
      assertEquals("dashboard", request.getValue("kind").jsonPrimitive.content)
      val session = request.getValue("session").jsonPrimitive.content
      assertEquals(
        baseline,
        mcp("get_session", buildJsonObject { put("session", session) }).data()["dashboard"],
      )
      assertEquals(
        true,
        ownerState(owner).getValue("agent").jsonObject.getValue("joined").jsonPrimitive.boolean,
        "the person sees the agent pick it up",
      )
      // The next listen only returns newer requests.
      val again =
        mcp(
            "await_request",
            buildJsonObject {
              put("link", agentLink)
              put("since", inbox.getValue("cursor").jsonPrimitive.long)
            },
          )
          .data()
      assertTrue(again.getValue("requests").jsonArray.isEmpty())
    }

    // Disconnecting ends the link for the agent; new requests fall back to a code.
    assertEquals(
      HttpStatusCode.OK,
      client
        .delete("/api/agent/link") { header(HttpHeaders.Authorization, "Bearer $ownerLink") }
        .status,
    )
    assertEquals(
      true,
      mcp("await_request", buildJsonObject { put("link", agentLink) })["isError"]
        ?.jsonPrimitive
        ?.boolean,
    )
  }

  @Test
  fun anAgentThatAsksFirstCanBeLinkedFromSettings() = testApplication {
    val relay = relay()
    application { routing { agentRelayRoutes(relay) } }
    val ownerLink =
      Json.parseToJsonElement(client.post("/api/agent/links").bodyAsText())
        .jsonObject
        .getValue("link")
        .jsonPrimitive
        .content
    val asked = mcp("request_access", buildJsonObject { put("agentName", "Codex") }).data()
    coroutineScope {
      val polling = async {
        mcp(
            "poll_access",
            buildJsonObject {
              put("requestToken", asked.getValue("requestToken").jsonPrimitive.content)
            },
          )
          .data()
      }
      delay(100)
      val claimed =
        client.post("/api/agent/link/claim") {
          header(HttpHeaders.Authorization, "Bearer $ownerLink")
          setBody(
            buildJsonObject { put("code", asked.getValue("code").jsonPrimitive.content) }.toString()
          )
        }
      assertEquals(HttpStatusCode.OK, claimed.status)
      val linked = polling.await()
      assertEquals("connected", linked.getValue("status").jsonPrimitive.content)
      assertNotNull(linked["link"])
    }
  }

  @Test
  fun linksSurviveARestart(): Unit = runBlocking {
    val file = kotlin.io.path.createTempDirectory("links").toFile().resolve("sessions.json")
    val before = relay()
    val created = before.links.create()
    val agentLink = assertNotNull(before.connect(created.code, "Claude"))
    AgentSessionFile(file, before).save()
    val after = relay()
    AgentSessionFile(file, after).load()
    assertEquals("Claude", after.links.state(created.owner)?.name)
    assertNotNull(after.links.awaitRequests(agentLink, 0))
  }

  @Test
  fun theAgentSeesTheDraftAsThePersonsAppDrewIt() = testApplication {
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
    val session =
      mcp("join_session", buildJsonObject { put("code", code) })
        .data()
        .getValue("session")
        .jsonPrimitive
        .content
    val before = mcp("get_render", buildJsonObject { put("session", session) }).data()
    assertEquals("none", before.getValue("status").jsonPrimitive.content)
    val png =
      java.util.Base64.getDecoder()
        .decode(
          "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        )
    suspend fun share(data: String) =
      client.post("/api/agent/session/render") {
        header(HttpHeaders.Authorization, "Bearer $owner")
        setBody(
          buildJsonObject {
            put("png", data)
            put("revision", 1)
          }
            .toString()
        )
      }
    assertEquals(HttpStatusCode.BadRequest, share("bm90IGEgcG5n").status, "only a PNG")
    val encoded = java.util.Base64.getEncoder().encodeToString(png)
    assertEquals(HttpStatusCode.OK, share(encoded).status)
    val seen = mcp("get_render", buildJsonObject { put("session", session) })
    val image = seen.getValue("content").jsonArray.first().jsonObject
    assertEquals("image", image.getValue("type").jsonPrimitive.content)
    assertEquals(encoded, image.getValue("data").jsonPrimitive.content)
    assertEquals(1, seen.data().getValue("revision").jsonPrimitive.int)
  }

  @Test
  fun abandonedSessionsArePrunedWhenNewOnesStart() {
    var now = 0L
    val relay = relay({ now })
    repeat(3) {
      val access = relay.dashboards.create("Tidy", baseline)
      relay.register(AgentRelay.Kind.Dashboard, access.owner, access.agent, "Tidy")
    }
    assertEquals(3, relay.size)
    // Nobody comes back; the stores expire them, and the next registration sweeps the relay.
    now += AgentRelay.JOIN_CODE_MS + DashboardAgentSessions.IDLE_MS
    val fresh = relay.dashboards.create("Again", baseline)
    val code = relay.register(AgentRelay.Kind.Dashboard, fresh.owner, fresh.agent, "Again")!!
    assertEquals(1, relay.size)
    assertNotNull(relay.join(code, null), "the new session's code still works")
  }

  @Test
  fun aClaimedRequestWhoseAgentNeverPollsIsSwept() {
    var now = 0L
    val relay = relay({ now })
    val access = relay.dashboards.create("Tidy", baseline)
    relay.register(AgentRelay.Kind.Dashboard, access.owner, access.agent, "Tidy")
    val pending = relay.requestAccess("Codex")
    assertEquals(AgentRelay.ClaimResult.Claimed, relay.claim(access.owner, pending.code))
    // The agent disconnects and never calls poll_access again.
    now += AgentRelay.REQUEST_MS
    relay.prune()
    assertEquals(0, relay.pendingRequests)
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
    val code = relay.register(AgentRelay.Kind.Dashboard, access.owner, access.agent, "Tidy")!!
    repeat(AgentRelay.MAX_FAILURES_PER_MINUTE) { relay.join("ZZZZ-ZZZZ", null) }
    assertNull(relay.join(code, null), "a guessing burst blocks even a right code for a minute")
  }

  @Test
  fun cardSessionJoinsAndOwnerSeesDraftRevision() = testApplication {
    val relay = relay()
    application { routing { agentRelayRoutes(relay) } }
    val document = buildJsonObject { put("id", "card-x") }
    val access = relay.cards.create("Bigger icon", document)
    val code = relay.register(AgentRelay.Kind.Card, access.owner, access.agent, "Bigger icon")!!
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
