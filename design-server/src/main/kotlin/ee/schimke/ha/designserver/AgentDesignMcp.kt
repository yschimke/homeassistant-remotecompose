package ee.schimke.ha.designserver

import kotlinx.serialization.json.*

/** A small stateless MCP surface scoped by the agent capability, never an HA identity. */
object AgentDesignMcp {
  fun handle(message: JsonObject, token: String, sessions: AgentDesignSessions): JsonObject? {
    val id = message["id"] ?: return null
    val method = (message["method"] as? JsonPrimitive)?.contentOrNull
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
    if (message["jsonrpc"] != JsonPrimitive("2.0") || method == null)
      return error(-32600, "Invalid request")
    if (!sessions.isAgent(token)) return error(-32001, "Design session unavailable")
    when (method) {
      "initialize" ->
        return result(
          buildJsonObject {
            put("protocolVersion", "2025-03-26")
            putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
            putJsonObject("serverInfo") {
              put("name", "terrazzo-card-session")
              put("version", "1")
            }
            put(
              "instructions",
              "Read the card request, then submit a complete draft. A person reviews and publishes it.",
            )
          }
        )
      "ping" -> return result(JsonObject(emptyMap()))
      "tools/list" ->
        return result(
          buildJsonObject {
            putJsonArray("tools") {
              add(
                buildJsonObject {
                  put("name", "get_card_request")
                  put("description", "Read the request, current card draft, and revision.")
                  putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {}
                  }
                }
              )
              add(
                buildJsonObject {
                  put("name", "submit_card_draft")
                  put(
                    "description",
                    "Submit the complete card document at the revision you read; the person reviews it.",
                  )
                  putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                      putJsonObject("revision") { put("type", "integer") }
                      putJsonObject("document") { put("type", "object") }
                    }
                    putJsonArray("required") {
                      add("revision")
                      add("document")
                    }
                  }
                }
              )
            }
          }
        )
      "tools/call" -> {
        val params = message["params"] as? JsonObject ?: return error(-32602, "Missing parameters")
        val name = (params["name"] as? JsonPrimitive)?.contentOrNull
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val data =
          when (name) {
            "get_card_request" -> {
              val session =
                sessions.readAgent(token) ?: return error(-32001, "Design session unavailable")
              buildJsonObject {
                put("instruction", session.instruction)
                put("document", session.document)
                put("revision", session.revision)
              }
            }
            "submit_card_draft" -> {
              val revision = (args["revision"] as? JsonPrimitive)?.longOrNull
              val document = args["document"] as? JsonObject
              if (revision == null || document == null)
                return error(-32602, "Expected revision and document")
              try {
                if (!sessions.submit(token, revision, document))
                  return error(-32001, "Design session unavailable")
              } catch (_: Exception) {
                return result(
                  toolResult(
                    buildJsonObject {
                      put(
                        "error",
                        "Invalid or stale draft; read the current revision and preserve the binding contract.",
                      )
                    },
                    true,
                  )
                )
              }
              buildJsonObject {
                put("status", "submitted")
                put("next", "The person reviews and publishes the draft.")
              }
            }
            else -> return error(-32602, "Unknown tool")
          }
        return result(toolResult(data, false))
      }
      else -> return error(-32601, "Method not found")
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
}
