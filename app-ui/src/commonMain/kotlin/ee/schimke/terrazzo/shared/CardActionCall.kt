package ee.schimke.terrazzo.shared

import kotlinx.serialization.json.*

/** A Home Assistant `call_service` command decoded from a card's `HaAction` payload. */
internal data class CardActionCall(
  val domain: String,
  val service: String,
  val entityId: String?,
  val serviceData: JsonObject = JsonObject(emptyMap()),
  val target: JsonObject? = null,
)

/**
 * Decodes an `HaAction` payload into the service call a live Home Assistant session should send, or
 * `null` for `None`. Unsupported action types throw.
 *
 * `Toggle` sends `homeassistant.toggle` targeting the entity, exactly as Android's
 * `DashboardActionDispatcher` and the iOS host do: it flips every toggleable domain, so the entity
 * prefix is never used as the service domain.
 */
internal fun cardActionCall(payload: String): CardActionCall? {
  val obj = Json.parseToJsonElement(payload).jsonObject
  return when (obj["type"]?.jsonPrimitive?.content?.substringAfterLast('.')) {
    "Toggle" ->
      CardActionCall(
        domain = "homeassistant",
        service = "toggle",
        entityId = requireNotNull(obj["entityId"]?.jsonPrimitive?.content),
      )
    "CallService" ->
      CardActionCall(
        domain = requireNotNull(obj["domain"]?.jsonPrimitive?.content),
        service = requireNotNull(obj["service"]?.jsonPrimitive?.content),
        entityId = obj["entityId"]?.jsonPrimitive?.contentOrNull,
        serviceData = obj["serviceData"] as? JsonObject ?: JsonObject(emptyMap()),
        target = (obj["target"] as? JsonObject)?.takeIf { it.isNotEmpty() },
      )
    "None" -> null
    else -> error("This action is not supported on this platform yet.")
  }
}

/**
 * Resolves a standalone desktop/Wasm URI to open for `Navigate` or `Url` `HaAction` payloads (when
 * no parent Home Assistant host handled them), or `null` for other action kinds.
 */
internal fun standaloneNavigationUri(payload: String, homeBaseUrl: String): String? {
  val obj = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
  return when (obj["type"]?.jsonPrimitive?.content?.substringAfterLast('.')) {
    "Url" -> {
      val url = obj["url"]?.jsonPrimitive?.contentOrNull?.trim() ?: return null
      url.takeIf {
        it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)
      }
    }
    "Navigate" -> {
      val path = obj["path"]?.jsonPrimitive?.contentOrNull?.trim() ?: return null
      if (!path.startsWith("/") || path.startsWith("//")) return null
      val base = homeBaseUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() } ?: return null
      base + path
    }
    else -> null
  }
}
