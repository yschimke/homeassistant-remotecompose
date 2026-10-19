package ee.schimke.terrazzo.shared

import kotlinx.serialization.json.*

/** A Home Assistant `call_service` command decoded from a card's `HaAction` payload. */
internal data class CardActionCall(
  val domain: String,
  val service: String,
  val entityId: String?,
  val serviceData: JsonObject = JsonObject(emptyMap()),
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
      )
    "None" -> null
    else -> error("This action is not supported on this platform yet.")
  }
}
