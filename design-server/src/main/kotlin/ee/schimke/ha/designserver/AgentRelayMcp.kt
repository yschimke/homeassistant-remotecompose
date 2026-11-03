package ee.schimke.ha.designserver

import kotlinx.serialization.json.*

/**
 * One MCP endpoint for every card and dashboard session, configured once in an agent and needing no
 * header: the agent joins with the short code the person's app shows (or asks for access and shows
 * the person a code), then passes the returned `session` to every other tool. The tools delegate
 * drafts to the per-kind handlers, which keep validating them.
 */
object AgentRelayMcp {
  const val INSTRUCTIONS =
    "You are editing a Home Assistant card or dashboard together with a person who is watching it " +
      "in the Terrazzo app. 1) Call join_session with the code the person gives you; if they have " +
      "none, call request_access, tell them its code, and call poll_access until approved. " +
      "2) Call get_session. 3) Use set_status while you work and send_message to talk to the " +
      "person. 4) Call submit_draft with the revision you read; the person sees it immediately " +
      "with their own live data. 5) Call await_feedback (it waits up to 25 s) in a loop, passing " +
      "back its cursor, and act on each comment until a decision of accepted or rejected arrives. " +
      "You can never apply a draft; the person does."

  suspend fun handle(message: JsonObject, bearer: String, relay: AgentRelay): JsonObject? {
    val id = message["id"] ?: return null
    fun error(code: Int, reason: String) = buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      putJsonObject("error") {
        put("code", code)
        put("message", reason)
      }
    }
    fun result(value: JsonElement) = buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      put("result", value)
    }
    fun tool(data: JsonObject, isError: Boolean = false) = result(toolResult(data, isError))
    fun failed(reason: String) = tool(buildJsonObject { put("error", reason) }, true)

    if (message["jsonrpc"] != JsonPrimitive("2.0")) return error(-32600, "Invalid request")
    val method =
      (message["method"] as? JsonPrimitive)?.contentOrNull
        ?: return error(-32600, "Invalid request")
    return when (method) {
      "initialize" ->
        result(
          buildJsonObject {
            put("protocolVersion", "2025-03-26")
            putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
            putJsonObject("serverInfo") {
              put("name", "terrazzo-agent-sessions")
              put("version", "1")
            }
            put("instructions", INSTRUCTIONS)
          }
        )
      "ping" -> result(JsonObject(emptyMap()))
      "tools/list" -> result(buildJsonObject { put("tools", TOOLS) })
      "tools/call" -> {
        val params = message["params"] as? JsonObject ?: return error(-32602, "Missing parameters")
        val name = (params["name"] as? JsonPrimitive)?.contentOrNull
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        fun arg(key: String) = (args[key] as? JsonPrimitive)?.contentOrNull
        val session = arg("session")?.takeIf { it.isNotBlank() } ?: bearer
        val gone = "This session ended or expired. Ask the person for a new code."
        when (name) {
          "join_session" -> {
            val joined =
              relay.join(arg("code").orEmpty(), arg("agentName"))
                ?: return failed(
                  "Unknown or expired code. Ask the person for the code in their app."
                )
            tool(joined.json("Call get_session next."))
          }
          "request_access" -> {
            val pending =
              try {
                relay.requestAccess(arg("agentName"))
              } catch (_: IllegalStateException) {
                return failed("Too many pending requests; try again shortly.")
              }
            tool(
              buildJsonObject {
                put("code", pending.code)
                put("requestToken", pending.poll)
                put("expiresInSeconds", pending.expiresInSeconds)
                put(
                  "next",
                  "Tell the person: in Terrazzo, open the card or dashboard, choose Connect an " +
                    "agent, and enter ${pending.code}. Then call poll_access with requestToken.",
                )
              }
            )
          }
          "poll_access" -> {
            val polled =
              relay.pollAccess(arg("requestToken").orEmpty())
                ?: return failed("Unknown or expired request; call request_access again.")
            val joined = polled.joined
            if (joined == null)
              tool(
                buildJsonObject {
                  put("status", "pending")
                  put("next", "The person hasn't entered the code yet; call poll_access again.")
                }
              )
            else tool(joined.json("Approved. Call get_session next."))
          }
          "get_session" -> {
            val kind = relay.seen(session, "Reading the request") ?: return failed(gone)
            val view = relay.agentView(session) ?: return failed(gone)
            val inner =
              when (kind) {
                AgentRelay.Kind.Card -> delegate(relay, kind, session, "get_card_request", args)
                AgentRelay.Kind.Dashboard ->
                  delegate(relay, kind, session, "get_dashboard_request", args)
              } ?: return failed(gone)
            tool(
              buildJsonObject {
                inner.forEach { (k, v) -> put(k, v) }
                put("kind", kind.wire)
                put("title", view.title)
                put("cursor", view.cursor)
                put("conversation", view.messages.json())
                view.decision?.let { put("decision", it) }
                put("personWatching", view.personWatching)
                put("expiresInSeconds", view.expiresInSeconds)
                put(
                  "submitWith",
                  if (kind == AgentRelay.Kind.Card)
                    "submit_draft with revision and the complete edited document"
                  else "submit_draft with revision, the complete dashboard and a summary",
                )
              }
            )
          }
          "set_status" -> {
            relay.seen(session, arg("status").orEmpty()) ?: return failed(gone)
            tool(buildJsonObject { put("status", "shown") })
          }
          "send_message" -> {
            val text = arg("text").orEmpty()
            if (text.isBlank() || text.length > AgentRelay.MAX_TEXT)
              return failed("Send between 1 and ${AgentRelay.MAX_TEXT} characters.")
            if (!relay.agentMessage(session, text)) return failed(gone)
            tool(buildJsonObject { put("status", "sent") })
          }
          "submit_draft" -> {
            val kind = relay.seen(session, "Submitting a draft") ?: return failed(gone)
            val summary = arg("summary").orEmpty().take(AgentRelay.MAX_TEXT)
            val forwarded = buildJsonObject {
              args["revision"]?.let { put("revision", it) }
              when (kind) {
                AgentRelay.Kind.Card -> args["document"]?.let { put("document", it) }
                AgentRelay.Kind.Dashboard -> {
                  args["dashboard"]?.let { put("dashboard", it) }
                  put("summary", summary)
                }
              }
            }
            val inner =
              when (kind) {
                AgentRelay.Kind.Card ->
                  delegateRaw(relay, kind, session, "submit_card_draft", forwarded)
                AgentRelay.Kind.Dashboard ->
                  delegateRaw(relay, kind, session, "submit_dashboard_draft", forwarded)
              } ?: return failed(gone)
            if (inner["isError"] == JsonPrimitive(true)) return result(inner)
            if (summary.isNotBlank()) relay.agentMessage(session, summary, "summary")
            relay.seen(session, "Draft submitted")
            tool(
              buildJsonObject {
                put("status", "submitted")
                put(
                  "next",
                  "The person sees the draft now. Call await_feedback to hear what they think.",
                )
              }
            )
          }
          "put_card_asset" -> {
            if (relay.seen(session) != AgentRelay.Kind.Card)
              return failed("Images can only be attached to a card session.")
            val inner =
              delegateRaw(relay, AgentRelay.Kind.Card, session, "put_card_asset", args)
                ?: return failed(gone)
            result(inner)
          }
          "await_feedback" -> {
            val since = (args["since"] as? JsonPrimitive)?.longOrNull ?: 0L
            val view = relay.awaitFeedback(session, since) ?: return failed(gone)
            val feedback = view.messages.filter { it.from == "person" }
            tool(
              buildJsonObject {
                put("cursor", view.cursor)
                put("feedback", feedback.json())
                view.decision?.let { put("decision", it) }
                put("personWatching", view.personWatching)
                put(
                  "next",
                  when {
                    view.decision == "accepted" -> "The person accepted the draft. You're done."
                    view.decision == "rejected" && feedback.none { it.kind == "comment" } ->
                      "The person rejected the draft. Stop, or ask what they would like."
                    feedback.isEmpty() -> "No feedback yet; call await_feedback again with cursor."
                    else -> "Act on the feedback, submit_draft, then await_feedback with cursor."
                  },
                )
              }
            )
          }
          else -> error(-32602, "Unknown tool")
        }
      }
      else -> error(-32601, "Method not found")
    }
  }

  /** A per-kind tool's structured result, or null when the store no longer has the session. */
  private fun delegate(
    relay: AgentRelay,
    kind: AgentRelay.Kind,
    token: String,
    tool: String,
    args: JsonObject,
  ): JsonObject? =
    delegateRaw(relay, kind, token, tool, args)?.let { it["structuredContent"] as? JsonObject }

  /** A per-kind tool's whole MCP tool result (content, structuredContent, isError). */
  private fun delegateRaw(
    relay: AgentRelay,
    kind: AgentRelay.Kind,
    token: String,
    tool: String,
    args: JsonObject,
  ): JsonObject? {
    val call = buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", 0)
      put("method", "tools/call")
      putJsonObject("params") {
        put("name", tool)
        put("arguments", JsonObject(args - "session"))
      }
    }
    val response =
      when (kind) {
        AgentRelay.Kind.Card -> AgentDesignMcp.handle(call, token, relay.cards)
        AgentRelay.Kind.Dashboard -> DashboardAgentMcp.handle(call, token, relay.dashboards)
      } ?: return null
    return response["result"] as? JsonObject
  }

  private fun AgentRelay.Joined.json(next: String) = buildJsonObject {
    put("status", "joined")
    put("session", session)
    put("kind", kind.wire)
    put("title", title)
    put("next", "Pass session to every other tool. $next")
  }

  fun List<AgentRelay.Message>.json() = buildJsonArray {
    forEach { m ->
      add(
        buildJsonObject {
          put("seq", m.seq)
          put("from", m.from)
          put("kind", m.kind)
          put("text", m.text)
          m.target?.let { put("target", it) }
        }
      )
    }
  }

  private fun toolResult(data: JsonObject, isError: Boolean) = buildJsonObject {
    putJsonArray("content") {
      add(
        buildJsonObject {
          put("type", "text")
          put("text", data.toString())
        }
      )
    }
    put("structuredContent", data)
    if (isError) put("isError", true)
  }

  private fun schema(vararg props: Pair<String, String>, required: List<String> = emptyList()) =
    buildJsonObject {
      put("type", "object")
      putJsonObject("properties") {
        props.forEach { (name, type) -> putJsonObject(name) { put("type", type) } }
      }
      if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(it) } }
    }

  private fun toolDef(name: String, description: String, input: JsonObject) = buildJsonObject {
    put("name", name)
    put("description", description)
    put("inputSchema", input)
  }

  private val TOOLS = buildJsonArray {
    add(
      toolDef(
        "join_session",
        "Join the session whose code the person's Terrazzo app shows (like K7QM-4MD2). Returns the session to pass to every other tool.",
        schema("code" to "string", "agentName" to "string", required = listOf("code")),
      )
    )
    add(
      toolDef(
        "request_access",
        "Ask to join when the person has no code: show them the returned code to enter in Terrazzo, then call poll_access.",
        schema("agentName" to "string"),
      )
    )
    add(
      toolDef(
        "poll_access",
        "Wait (up to 25 s) for the person to enter the request_access code. Returns the session once approved.",
        schema("requestToken" to "string", required = listOf("requestToken")),
      )
    )
    add(
      toolDef(
        "get_session",
        "Read the request, the current card document or dashboard, its revision and the conversation so far.",
        schema("session" to "string", required = listOf("session")),
      )
    )
    add(
      toolDef(
        "set_status",
        "Show the person what you are doing, in a few words (for example: Drafting a compact layout).",
        schema("session" to "string", "status" to "string", required = listOf("session", "status")),
      )
    )
    add(
      toolDef(
        "send_message",
        "Say something to the person, such as a question or what you changed. They see it in the app.",
        schema("session" to "string", "text" to "string", required = listOf("session", "text")),
      )
    )
    add(
      toolDef(
        "submit_draft",
        "Submit a complete draft at the revision you read: document for a card session, dashboard for a dashboard session. Add a one-line summary. The person sees it at once and decides; you never apply it.",
        buildJsonObject {
          put("type", "object")
          putJsonObject("properties") {
            putJsonObject("session") { put("type", "string") }
            putJsonObject("revision") { put("type", "integer") }
            putJsonObject("document") { put("type", "object") }
            putJsonObject("dashboard") { put("type", "object") }
            putJsonObject("summary") { put("type", "string") }
          }
          putJsonArray("required") {
            add("session")
            add("revision")
          }
        },
      )
    )
    add(
      toolDef(
        "put_card_asset",
        "Card sessions: attach one PNG, JPEG or WebP image before submitting; put the returned binding under document.assets with the same key.",
        schema(
          "session" to "string",
          "key" to "string",
          "imageBase64" to "string",
          required = listOf("session", "key", "imageBase64"),
        ),
      )
    )
    add(
      toolDef(
        "await_feedback",
        "Wait up to 25 s for the person's comments or decision after cursor (0 at first). Call it again with the returned cursor until a decision arrives.",
        schema("session" to "string", "since" to "integer", required = listOf("session")),
      )
    )
  }
}
