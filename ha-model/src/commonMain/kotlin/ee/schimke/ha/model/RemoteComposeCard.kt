package ee.schimke.ha.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The Remote Compose Lovelace card (`custom_components/terrazzo/frontend/remote-compose-card.js`):
 * a wrapper that asks for its inner `card:` to be drawn from a card design. Every host that draws
 * card designs draws it as that inner card.
 */
const val REMOTE_COMPOSE_CARD_TYPE: String = "custom:remote-compose-card"

/** The card a Remote Compose card wraps, or null for any other card or one without a `card:`. */
fun remoteComposeInnerCard(card: CardConfig): CardConfig? {
  if (card.type != REMOTE_COMPOSE_CARD_TYPE) return null
  val inner = card.raw["card"] as? JsonObject ?: return null
  val type = (inner["type"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
  return type?.takeIf { it != REMOTE_COMPOSE_CARD_TYPE }?.let { CardConfig(it, inner) }
}

/** The card style a Remote Compose card names (`card_style:`), or null to use the host's. */
fun remoteComposeCardStyle(card: CardConfig): String? =
  if (card.type != REMOTE_COMPOSE_CARD_TYPE) null
  else (card.raw["card_style"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

/** The chosen library or pack design id (`design_id:`), or null. */
fun remoteComposeDesignId(card: CardConfig): String? =
  if (card.type != REMOTE_COMPOSE_CARD_TYPE) null
  else (card.raw["design_id"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

/** The direct RC template URL (`template_url:`), or null. */
fun remoteComposeTemplateUrl(card: CardConfig): String? =
  if (card.type != REMOTE_COMPOSE_CARD_TYPE) null
  else
    (card.raw["template_url"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

/** The explicit HA device id (`device_id:`), or null. */
fun remoteComposeDeviceId(card: CardConfig): String? =
  if (card.type != REMOTE_COMPOSE_CARD_TYPE) null
  else (card.raw["device_id"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

/** The explicit multi-entity semantic bindings map (`bindings:`), or null. */
fun remoteComposeBindings(card: CardConfig): JsonObject? =
  if (card.type != REMOTE_COMPOSE_CARD_TYPE) null else card.raw["bindings"] as? JsonObject

/** The explicit semantic button actions map (`actions:`), or null. */
fun remoteComposeActions(card: CardConfig): JsonObject? =
  if (card.type != REMOTE_COMPOSE_CARD_TYPE) null else card.raw["actions"] as? JsonObject
