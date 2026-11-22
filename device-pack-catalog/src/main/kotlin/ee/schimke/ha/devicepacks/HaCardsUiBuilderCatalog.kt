package ee.schimke.ha.devicepacks

import ee.schimke.ha.cards.CardDesignDocument
import ee.schimke.livery.uibuilder.capture.UiBuilderCatalog
import ee.schimke.livery.uibuilder.capture.UiComponent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The `ha-cards` UI Builder catalog for Livery's `.uid` capture (`livery-uibuilder-capture`),
 * registered in `META-INF/services`: a device pack design is drawn by this repository's own
 * renderer, [CardDesignDocument], as the previews draw it.
 *
 * [CardDesignDocument] draws a whole document, so the design's root component draws it all and no
 * other component is called; each id is still registered so Livery's check that every node has a
 * component holds. Its components bind Home Assistant's named values themselves, so the contract is
 * checked by `livery build`, which refuses a template that reads a value its contract doesn't
 * declare.
 */
class HaCardsUiBuilderCatalog : UiBuilderCatalog {
  override val systemIds: Set<String> = setOf(CardDesignDocument.CATALOG)

  override val components: Map<String, UiComponent> =
    CardDesignDocument.componentIds.associateWith {
      { node, modifier ->
        check(node.id == node.design.document.roots().single()) {
          "${node.id}: drawn by its design's root, not by itself"
        }
        CardDesignDocument.parse(node.design.document.toString()).Render(modifier)
      }
    }

  /**
   * The document with each example entity a design was made with (`cover.garage_door`) named as the
   * contract's slots are (`cover.s0`, then `cover.s1`), by domain in the order they appear: the
   * names a host pushes a card's entities under.
   */
  override fun prepare(document: JsonObject): JsonObject {
    val nodes = document["nodes"]?.jsonObject ?: return document
    val slots = linkedMapOf<String, String>()
    val perDomain = mutableMapOf<String, Int>()
    for (node in nodes.values) {
      val entity = node.jsonObject.property(ENTITY_ID) ?: continue
      if (entity.isBlank() || entity in slots) continue
      val domain = entity.substringBefore('.')
      val index = perDomain.getOrDefault(domain, 0)
      perDomain[domain] = index + 1
      slots[entity] = "$domain.s$index"
    }
    if (slots.isEmpty()) return document
    val renamed = nodes.mapValues { (_, node) ->
      val n = node.jsonObject
      val entity = n.property(ENTITY_ID) ?: return@mapValues node
      val slot = slots[entity] ?: return@mapValues node
      val properties = n.getValue("properties").jsonObject
      val value =
        JsonObject(properties.getValue(ENTITY_ID).jsonObject + ("value" to JsonPrimitive(slot)))
      JsonObject(n + ("properties" to JsonObject(properties + (ENTITY_ID to value))))
    }
    return JsonObject(document + ("nodes" to JsonObject(renamed)))
  }

  private fun JsonObject.property(name: String): String? {
    val p = this["properties"]?.jsonObject?.get(name)?.jsonObject ?: return null
    if (p["type"]?.jsonPrimitive?.contentOrNull != "string") return null
    return p["value"]?.jsonPrimitive?.contentOrNull
  }

  private fun JsonObject.roots(): List<String> =
    (this["roots"] as? kotlinx.serialization.json.JsonArray)
      .orEmpty()
      .map(JsonElement::jsonPrimitive)
      .map { it.content }

  private companion object {
    const val ENTITY_ID = "entityId"
  }
}
