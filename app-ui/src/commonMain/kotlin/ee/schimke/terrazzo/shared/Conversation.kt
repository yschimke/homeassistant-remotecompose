package ee.schimke.terrazzo.shared

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import kotlinx.serialization.json.*

internal data class ConversationMessage(val timestamp: Double, val sender: String, val text: String)

/** MeshCore message entities are activity flags; only logbook message entries contain chat text. */
internal fun conversationEntity(card: CardConfig): String? {
  fun isConversation(id: String) =
    id.startsWith("binary_sensor.meshcore_") && id.endsWith("_messages")
  val entity = (card.raw["entity"] as? JsonPrimitive)?.contentOrNull
  if (
    entity != null &&
      isConversation(entity) &&
      card.type in setOf("tile", "entity", "custom:terrazzo-chat")
  )
    return entity
  if (card.type !in setOf("logbook", "custom:terrazzo-chat")) return null
  val entries =
    (card.raw["entities"] as? JsonArray)
      ?.mapNotNull { entry ->
        (entry as? JsonPrimitive)?.contentOrNull
          ?: ((entry as? JsonObject)?.get("entity") as? JsonPrimitive)?.contentOrNull
      }
      .orEmpty()
  return entries.singleOrNull()?.takeIf(::isConversation)
}

internal fun conversationMessages(rows: JsonArray, entityId: String): List<ConversationMessage> =
  rows
    .mapNotNull { row ->
      val obj = row as? JsonObject ?: return@mapNotNull null
      if ((obj["entity_id"] as? JsonPrimitive)?.contentOrNull != entityId) return@mapNotNull null
      val message =
        (obj["message"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
          ?: return@mapNotNull null
      val whenValue =
        (obj["when"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
          ?: return@mapNotNull null
      // Logbook text is "<channel> sender: text" or "sender: text". Preserve colons in the body.
      val cleaned = message.replaceFirst(Regex("^<[^>]+>\\s*"), "")
      val split = cleaned.indexOf(": ")
      ConversationMessage(
        whenValue,
        if (split >= 0) cleaned.substring(0, split) else "Message",
        if (split >= 0) cleaned.substring(split + 2) else cleaned,
      )
    }
    .distinct()
    .sortedBy { it.timestamp }
    .takeLast(100)

internal data class ConversationSend(val service: String, val data: JsonObject)

/** Resolve a recipient from real entity attributes. Never send to an inferred channel/contact. */
internal fun conversationSend(
  entity: EntityState,
  entryId: String?,
  text: String,
): ConversationSend? {
  if (
    text.isBlank() ||
      text.encodeToByteArray().size > 120 ||
      entity.state in setOf("unknown", "unavailable")
  )
    return null
  val channel =
    (entity.attributes["channel_index"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
  val contact =
    (entity.attributes["public_key"] as? JsonPrimitive)?.contentOrNull?.takeIf {
      Regex("[0-9a-fA-F]{12}").matches(it)
    }
  if (channel == null && contact == null) return null
  return ConversationSend(
    if (channel != null) "send_channel_message" else "send_message",
    buildJsonObject {
      put("message", text)
      if (channel != null) put("channel_idx", channel) else put("pubkey_prefix", contact!!)
      entryId?.let { put("entry_id", it) }
    },
  )
}
