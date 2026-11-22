package ee.schimke.ha.designserver

import ee.schimke.livery.sessions.DraftStore
import ee.schimke.livery.sessions.server.DraftResult
import ee.schimke.livery.sessions.server.SessionKind
import ee.schimke.livery.sessions.server.SessionServer
import ee.schimke.livery.sessions.server.SessionTool
import ee.schimke.livery.sessions.server.SessionToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The design server's two kinds of agent session on Livery's session server
 * (`livery-sessions-server`): a card and a dashboard. Livery keeps the relay, the MCP endpoint and
 * the routes; these keep the drafts in [AgentDesignSessions] and [DashboardAgentSessions] and
 * validate them through the same per-kind handlers ([AgentDesignMcp], [DashboardAgentMcp]) as the
 * per-session HTTP endpoints, so both ways in hold a draft to one gate.
 */
class CardSessionKind(
  val sessions: AgentDesignSessions,
  /** What the agent can learn and see beyond the request: [AgentGuide]. */
  private val guide: AgentGuide = AgentGuide(),
) : SessionKind {
  override val id = ID

  override val store: DraftStore =
    object : DraftStore {
      override fun revision(owner: String) = sessions.read(owner)?.revision

      override fun touch(owner: String) = sessions.touch(owner)

      override fun revoke(owner: String) = sessions.revoke(owner)
    }

  override fun read(agent: String): JsonObject? =
    call(agent, "get_card_request", JsonObject(emptyMap())).data()

  override fun submit(agent: String, revision: Long, draft: JsonObject): DraftResult =
    call(
        agent,
        "submit_card_draft",
        buildJsonObject {
          put("revision", revision)
          draft["document"]?.let { put("document", it) }
        },
      )
      .draft()

  override val submitWith = "submit_draft with revision and the complete edited document"

  override val tools: List<SessionTool> =
    listOf(
      object : SessionTool {
        override val name = "put_card_asset"
        override val description =
          "Card sessions: attach one PNG, JPEG or WebP image before submitting; put the returned " +
            "binding under document.assets with the same key."
        override val inputSchema = schema("key" to "string", "imageBase64" to "string")

        override suspend fun call(agent: String, arguments: JsonObject): SessionToolResult =
          call(agent, "put_card_asset", arguments).tool()
      },
      object : SessionTool {
        override val name = "get_card_guide"
        override val description =
          "Card sessions: what a draft may be built with (components, modifiers, value forms, " +
            "expressions, clock values), the card's bindings with their current and sample " +
            "values, the shared values, and the rules for a draft."
        override val inputSchema = schema()

        override suspend fun call(agent: String, arguments: JsonObject): SessionToolResult =
          sessions.context(agent)?.let { SessionToolResult.Done(guide.cardGuide(it)) } ?: GONE
      },
      object : SessionTool {
        override val name = "check_card_draft"
        override val description =
          "Card sessions: check a complete draft document against the card's binding contract " +
            "and what the compiler draws, without submitting it. Returns ok and the problems."
        override val inputSchema = schema("document" to "object")

        override suspend fun call(agent: String, arguments: JsonObject): SessionToolResult {
          val session = sessions.context(agent) ?: return GONE
          val document =
            arguments["document"] as? JsonObject
              ?: return SessionToolResult.Failed("Pass the complete draft as document.")
          return SessionToolResult.Done(guide.checkCard(session, document))
        }
      },
      object : SessionTool {
        override val name = "render_card"
        override val description =
          "Card sessions: draw a draft as a PNG, with the card's current values (data " +
            "\"current\", the default) or sample ones (\"sample\"). document defaults to the " +
            "submitted draft; dark draws it in dark mode."
        override val inputSchema = buildJsonObject {
          put("type", "object")
          putJsonObject("properties") {
            putJsonObject("data") {
              put("type", "string")
              putJsonArray("enum") {
                add(JsonPrimitive(AgentGuide.CURRENT))
                add(JsonPrimitive(AgentGuide.SAMPLE))
              }
            }
            putJsonObject("document") { put("type", "object") }
            putJsonObject("dark") { put("type", "boolean") }
          }
        }

        override suspend fun call(agent: String, arguments: JsonObject): SessionToolResult =
          sessions.context(agent)?.let { guide.renderCard(it, arguments) } ?: GONE
      },
    )

  override val httpPath = "/api/studio/agent-session"

  override fun attached(server: SessionServer) {
    // Store callbacks run under the store's lock: SessionServer.changed takes none.
    sessions.onChange = server::changed
  }

  override fun save(): JsonElement = JSON.encodeToJsonElement(SAVED, sessions.snapshot())

  override fun restore(saved: JsonElement) =
    sessions.restore(JSON.decodeFromJsonElement(SAVED, saved))

  private fun call(agent: String, tool: String, arguments: JsonObject) =
    AgentDesignMcp.handle(toolCall(tool, arguments), agent, sessions)

  companion object {
    const val ID = "card"
    private val SAVED =
      kotlinx.serialization.builtins.ListSerializer(AgentDesignSessions.Saved.serializer())
  }
}

class DashboardSessionKind(
  val sessions: DashboardAgentSessions,
  /** What the agent can learn and see beyond the request: [AgentGuide]. */
  private val guide: AgentGuide = AgentGuide(),
) : SessionKind {
  override val id = ID

  override val store: DraftStore =
    object : DraftStore {
      override fun revision(owner: String) = sessions.read(owner)?.revision

      override fun touch(owner: String) = sessions.touch(owner)

      override fun revoke(owner: String) = sessions.revoke(owner)

      // Agents that still read the dashboard request directly see the comment as a note.
      override fun note(owner: String, text: String) {
        sessions.note(owner, text)
      }
    }

  override fun read(agent: String): JsonObject? =
    call(agent, "get_dashboard_request", JsonObject(emptyMap())).data()

  override fun submit(agent: String, revision: Long, draft: JsonObject): DraftResult =
    call(
        agent,
        "submit_dashboard_draft",
        buildJsonObject {
          put("revision", revision)
          draft["dashboard"]?.let { put("dashboard", it) }
          // The store keeps the summary with the draft; the server also posts it to the
          // conversation.
          put(
            "summary",
            (draft["summary"] as? JsonPrimitive)
              ?.contentOrNull
              .orEmpty()
              .trim()
              .take(AgentRelay.MAX_TEXT),
          )
        },
      )
      .draft()

  override val submitWith = "submit_draft with revision, the complete dashboard and a summary"

  override val tools: List<SessionTool> =
    listOf(
      object : SessionTool {
        override val name = "get_dashboard_guide"
        override val description =
          "Dashboard sessions: the card types Terrazzo draws, the library designs a card can " +
            "name with design_id, and how to see a draft (render_dashboard, get_render)."
        override val inputSchema = schema()

        override suspend fun call(agent: String, arguments: JsonObject): SessionToolResult =
          if (sessions.read(agent, agentOnly = true) == null) GONE
          else SessionToolResult.Done(guide.dashboardGuide())
      },
      object : SessionTool {
        override val name = "render_dashboard"
        override val description =
          "Dashboard sessions: draw a dashboard's cards with made-up sample states (never the " +
            "person's: get_render shows theirs after submit_draft). dashboard defaults to the " +
            "submitted draft; view draws one view (from 0); dark draws dark mode."
        override val inputSchema = buildJsonObject {
          put("type", "object")
          putJsonObject("properties") {
            putJsonObject("dashboard") { put("type", "object") }
            putJsonObject("view") { put("type", "integer") }
            putJsonObject("dark") { put("type", "boolean") }
          }
        }

        override suspend fun call(agent: String, arguments: JsonObject): SessionToolResult {
          val session = sessions.read(agent, agentOnly = true) ?: return GONE
          val dashboard = arguments["dashboard"] as? JsonObject ?: session.dashboard
          return guide.renderDashboard(dashboard, arguments)
        }
      },
    )

  override val httpPath = "/api/studio/dashboard-session"

  override fun attached(server: SessionServer) {
    sessions.onChange = server::changed
  }

  override fun save(): JsonElement = JSON.encodeToJsonElement(SAVED, sessions.snapshot())

  override fun restore(saved: JsonElement) =
    sessions.restore(JSON.decodeFromJsonElement(SAVED, saved))

  private fun call(agent: String, tool: String, arguments: JsonObject) =
    DashboardAgentMcp.handle(toolCall(tool, arguments), agent, sessions)

  companion object {
    const val ID = "dashboard"
    private val SAVED =
      kotlinx.serialization.builtins.ListSerializer(DashboardAgentSessions.Saved.serializer())
  }
}

private val JSON = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
}

private val GONE =
  SessionToolResult.Failed("This session ended or expired. Ask the person for a new code.")

/** The per-kind handlers' "this session is gone" JSON-RPC error. */
private const val UNAVAILABLE = -32001

private fun toolCall(tool: String, arguments: JsonObject) = buildJsonObject {
  put("jsonrpc", "2.0")
  put("id", 0)
  put("method", "tools/call")
  putJsonObject("params") {
    put("name", tool)
    put("arguments", arguments)
  }
}

/** What a per-kind handler answered: its tool result, or why it didn't run the tool. */
private sealed interface Outcome {
  data class Ok(val data: JsonObject) : Outcome

  data class Refused(val reason: String) : Outcome

  data object Gone : Outcome
}

private fun JsonObject?.outcome(): Outcome {
  if (this == null) return Outcome.Gone
  (this["error"] as? JsonObject)?.let { e ->
    return if ((e["code"] as? JsonPrimitive)?.contentOrNull == UNAVAILABLE.toString()) Outcome.Gone
    else Outcome.Refused((e["message"] as? JsonPrimitive)?.contentOrNull ?: "Refused")
  }
  val result = this["result"] as? JsonObject ?: return Outcome.Gone
  val data = result["structuredContent"] as? JsonObject ?: JsonObject(emptyMap())
  return if (result["isError"] == JsonPrimitive(true))
    Outcome.Refused((data["error"] as? JsonPrimitive)?.contentOrNull ?: "Refused")
  else Outcome.Ok(data)
}

private fun JsonObject?.data(): JsonObject? = (outcome() as? Outcome.Ok)?.data

private fun JsonObject?.draft(): DraftResult =
  when (val o = outcome()) {
    // The handler's own "submitted"/"next" give way to the server's.
    is Outcome.Ok -> DraftResult.Accepted()
    is Outcome.Refused -> DraftResult.Refused(o.reason)
    Outcome.Gone -> DraftResult.Gone
  }

private fun JsonObject?.tool(): SessionToolResult =
  when (val o = outcome()) {
    is Outcome.Ok -> SessionToolResult.Done(o.data)
    is Outcome.Refused -> SessionToolResult.Failed(o.reason)
    Outcome.Gone -> GONE
  }

private fun schema(vararg props: Pair<String, String>) = buildJsonObject {
  put("type", "object")
  putJsonObject("properties") {
    props.forEach { (name, type) -> putJsonObject(name) { put("type", type) } }
  }
  putJsonArray("required") { props.forEach { add(JsonPrimitive(it.first)) } }
}
