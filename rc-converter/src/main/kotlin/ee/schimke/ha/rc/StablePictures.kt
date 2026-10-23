package ee.schimke.ha.rc

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Picture URLs a converter card can bake without baking the picture: each picture-entity slot's
 * `entity_picture` ([cardPictureEntityIds]) replaced by a stable placeholder naming its entity,
 * which the player's image loader resolves to the live URL as it loads ([resolve]).
 *
 * A camera's `entity_picture` changes with every rotated token or new frame. Baked as is, each
 * change was a new document, captured again; with the placeholder the document stays the same and
 * the host only tells the player to load its pictures again (`HaRcComposePlayer`'s image epoch),
 * the last picture drawn until the next one lands. Whether a slot has a picture at all still
 * changes the document (an image or an icon), so it stays in the key ([presence]).
 *
 * For the Compose player in the app's dashboard: a host that can't resolve the placeholder (a
 * launcher widget, a capture baked for another surface) keeps the real URLs.
 */
object StablePictures {
  private const val SCHEME = "rc-entity-picture://"

  /** [snapshot] with [card]'s picture-entity slots' pictures as placeholders. */
  fun snapshot(card: CardConfig, snapshot: HaSnapshot): HaSnapshot {
    val ids = cardPictureEntityIds(card)
    if (ids.isEmpty()) return snapshot
    var changed = false
    val states =
      snapshot.states.mapValues { (id, entity) ->
        if (id !in ids || picture(entity.attributes) == null) entity
        else {
          changed = true
          entity.copy(
            attributes = JsonObject(entity.attributes + (PICTURE to JsonPrimitive("$SCHEME$id")))
          )
        }
      }
    return if (changed) snapshot.copy(states = states) else snapshot
  }

  /** The live URL for a placeholder [url] in [snapshot]; null when [url] is no placeholder. */
  fun resolve(url: String, snapshot: HaSnapshot?): String? {
    if (!url.startsWith(SCHEME)) return null
    return snapshot?.states?.get(url.removePrefix(SCHEME))?.attributes?.let(::picture)
  }

  /**
   * Which of [card]'s picture-entity slots have a picture: what the document's shape depends on.
   */
  fun presence(card: CardConfig, snapshot: HaSnapshot): String =
    cardPictureEntityIds(card).joinToString(";") { id ->
      val has = snapshot.states[id]?.attributes?.let(::picture) != null
      "$id=${if (has) 1 else 0}"
    }

  private fun picture(attributes: JsonObject): String? =
    (attributes[PICTURE] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

  private const val PICTURE = "entity_picture"
}
