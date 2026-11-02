package ee.schimke.ha.designserver

import ee.schimke.ha.design.HaSharedValues
import java.util.Base64
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
                  put("name", "put_card_asset")
                  put(
                    "description",
                    "Attach one PNG, JPEG or WebP image to this session before submitting a draft. Images expire with the session.",
                  )
                  putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                      putJsonObject("key") { put("type", "string") }
                      putJsonObject("imageBase64") { put("type", "string") }
                    }
                    putJsonArray("required") {
                      add("key")
                      add("imageBase64")
                    }
                  }
                }
              )
              add(
                buildJsonObject {
                  put("name", "submit_card_draft")
                  put(
                    "description",
                    "Submit the complete card document at the revision you read; the person reviews it. " +
                      "For each asset/image, first call put_card_asset and put its returned binding under document.assets using the same key.",
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
            "put_card_asset" -> {
              val key =
                (args["key"] as? JsonPrimitive)?.contentOrNull
                  ?: return error(-32602, "Missing asset key")
              val encoded =
                (args["imageBase64"] as? JsonPrimitive)?.contentOrNull
                  ?: return error(-32602, "Missing image")
              val image =
                try {
                  require(encoded.length <= 1_400_000)
                  Base64.getDecoder().decode(encoded)
                } catch (_: IllegalArgumentException) {
                  return result(toolResult(buildJsonObject { put("error", "Invalid image") }, true))
                }
              try {
                if (!sessions.putAsset(token, key, image))
                  return error(-32001, "Design session unavailable")
              } catch (_: IllegalArgumentException) {
                return result(toolResult(buildJsonObject { put("error", "Image rejected") }, true))
              }
              val asset =
                sessions.asset(token, key) ?: return error(-32001, "Design session unavailable")
              buildJsonObject {
                put("key", key)
                put("mediaType", asset.mediaType)
                put("sha256", asset.sha256)
                putJsonObject("binding") {
                  put("mediaType", asset.mediaType)
                  put("contentDigest", "sha256:${asset.sha256}")
                  putJsonObject("source") {
                    put("type", "uploaded")
                    put("storageKey", "sha256:${asset.sha256}")
                  }
                }
              }
            }
            "get_card_request" -> {
              val session =
                sessions.readAgent(token) ?: return error(-32001, "Design session unavailable")
              buildJsonObject {
                put("instruction", session.instruction)
                put("document", session.document)
                put("revision", session.revision)
                putJsonArray("sharedValues") {
                  HaSharedValues.values.forEach { shared ->
                    add(
                      buildJsonObject {
                        put("name", shared.name)
                        put("description", shared.description)
                        putJsonObject("definition") {
                          put("type", "value")
                          when (shared.kind) {
                            HaSharedValues.Kind.Text -> {
                              put("valueType", "string")
                              put("initialValue", shared.daytime as String)
                            }
                            HaSharedValues.Kind.Bool -> {
                              put("valueType", "bool")
                              put("initialValue", shared.daytime as Boolean)
                            }
                            HaSharedValues.Kind.Float -> {
                              put("valueType", "float")
                              put("initialValue", (shared.daytime as Number).toFloat())
                            }
                          }
                          put("nullable", false)
                          put("persistence", "design")
                        }
                      }
                    )
                  }
                }
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
