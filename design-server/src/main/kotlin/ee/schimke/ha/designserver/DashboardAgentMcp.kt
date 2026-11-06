package ee.schimke.ha.designserver

import kotlinx.serialization.json.*

object DashboardAgentMcp {
  fun handle(message: JsonObject, token: String, sessions: DashboardAgentSessions): JsonObject? {
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
    if (message["jsonrpc"] != JsonPrimitive("2.0")) return error(-32600, "Invalid request")
    val method =
      (message["method"] as? JsonPrimitive)?.contentOrNull
        ?: return error(-32600, "Invalid request")
    val current =
      sessions.read(token, agentOnly = true) ?: return error(-32001, "Session unavailable")
    return when (method) {
      "initialize" ->
        result(
          buildJsonObject {
            put("protocolVersion", "2025-03-26")
            putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
            putJsonObject("serverInfo") {
              put("name", "terrazzo-dashboard-session")
              put("version", "1")
            }
            put(
              "instructions",
              "Read the dashboard request and submit a complete draft. The person reviews and applies it in Home Assistant.",
            )
          },
        )
      "ping" -> result(JsonObject(emptyMap()))
      "tools/list" ->
        result(
          buildJsonObject {
            putJsonArray("tools") {
              add(
                buildJsonObject {
                  put("name", "get_dashboard_request")
                  put("description", "Read the dashboard, request, revision and notes.")
                  putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {}
                  }
                },
              )
              add(
                buildJsonObject {
                  put("name", "submit_dashboard_draft")
                  put("description", "Submit the whole dashboard for the person's review.")
                  putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                      putJsonObject("revision") { put("type", "integer") }
                      putJsonObject("dashboard") { put("type", "object") }
                      putJsonObject("summary") { put("type", "string") }
                    }
                    putJsonArray("required") {
                      add("revision")
                      add("dashboard")
                    }
                  }
                },
              )
            }
          },
        )
      "tools/call" -> {
        val params = message["params"] as? JsonObject ?: return error(-32602, "Missing parameters")
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val payload =
          when ((params["name"] as? JsonPrimitive)?.contentOrNull) {
            "get_dashboard_request" ->
              buildJsonObject {
                put("request", current.request)
                put("dashboard", current.dashboard)
                put("revision", current.revision)
                putJsonArray("notes") { current.notes.forEach { add(it) } }
              }
            "submit_dashboard_draft" -> {
              val revision =
                (args["revision"] as? JsonPrimitive)?.longOrNull
                  ?: return error(-32602, "Missing revision")
              val dashboard =
                args["dashboard"] as? JsonObject ?: return error(-32602, "Missing dashboard")
              val summary = (args["summary"] as? JsonPrimitive)?.contentOrNull.orEmpty()
              try {
                if (!sessions.submit(token, revision, dashboard, summary))
                  return error(-32001, "Session unavailable")
              } catch (_: IllegalArgumentException) {
                return result(
                  toolResult(
                    buildJsonObject {
                      put("error", "Invalid or stale draft; read the current revision.")
                    },
                    true,
                  ),
                )
              }
              buildJsonObject {
                put("status", "submitted")
                put("next", "The person reviews and applies the draft.")
              }
            }
            else -> return error(-32602, "Unknown tool")
          }
        result(toolResult(payload, false))
      }
      else -> error(-32601, "Method not found")
    }
  }

  private fun toolResult(data: JsonObject, isError: Boolean) = buildJsonObject {
    putJsonArray("content") {
      add(
        buildJsonObject {
          put("type", "text")
          put("text", data.toString())
        },
      )
    }
    put("structuredContent", data)
    if (isError) put("isError", true)
  }
}
