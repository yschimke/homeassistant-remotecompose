package ee.schimke.terrazzo.shared

import ee.schimke.ha.model.CardConfig
import kotlinx.serialization.json.*

internal data class CardServiceAction(val domain: String, val service: String, val data: JsonObject)

/** Only declared HA service actions; templates and arbitrary JavaScript are never executed. */
internal fun cardServiceAction(card: CardConfig): CardServiceAction? {
  val tap = card.raw["tap_action"] as? JsonObject
  val kind = (tap?.get("action") as? JsonPrimitive)?.contentOrNull
  val entity = (card.raw["entity"] as? JsonPrimitive)?.contentOrNull
  val target = tap?.get("target") as? JsonObject
  if (target != null && target.keys.any { it != "entity_id" }) return null
  val declared =
    when (kind) {
      "perform-action" -> (tap?.get("perform_action") as? JsonPrimitive)?.contentOrNull
      "call-service" -> (tap?.get("service") as? JsonPrimitive)?.contentOrNull
      null ->
        when (entity?.substringBefore('.')) {
          "button",
          "input_button" -> entity!!.substringBefore('.') + ".press"
          "script",
          "scene" -> entity!!.substringBefore('.') + ".turn_on"
          else -> null
        }
      else -> null
    } ?: return null
  if (!Regex("[a-z_]+\\.[a-z_]+").matches(declared)) return null
  return CardServiceAction(
    declared.substringBefore('.'),
    declared.substringAfter('.'),
    buildJsonObject {
      val source = tap?.get("data") as? JsonObject ?: tap?.get("service_data") as? JsonObject
      source?.forEach { (k, v) -> put(k, v) }
      val target = tap?.get("target") as? JsonObject
      val targetEntity =
        target?.get("entity_id") ?: source?.get("entity_id") ?: entity?.let(::JsonPrimitive)
      targetEntity?.let { put("entity_id", it) }
    },
  )
}
