package ee.schimke.ha.designserver

import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A card's data contract: what a design for it can bind, and so what a designer has to work with.
 *
 * - [slots]: the entities the card binds, by position. A design names them by slot
 *   (`<domain>.s<N>`), never by entity id, so one design serves every card of its shape.
 * - [bindings]: every named value a client pushes, with its type and an example value: per slot
 *   `name`, `state`, `is_on`, `state_int`, `status`, `numeric_state`, plus card-level values
 *   (`card.title`, …). This is the binding schema in docs/architecture/card-design-server.md.
 * - [properties]: the generation properties that pick the design's structure, when it comes from a
 *   library design. They aren't bindable: a design has one structure per shape.
 *
 * It comes from a library design ([fromDesign]: the template's own decoded bindings, with its
 * catalog example's values) or from any card's Lovelace config and states ([fromCard]: the slot
 * bindings `CardDesignSlots` derives, the same ones the app pushes).
 */
@Serializable
data class CardSchema(
  val cardType: String,
  /** `design:<id>` or `card`. */
  val source: String,
  val properties: Map<String, String> = emptyMap(),
  val slots: List<SlotSchema> = emptyList(),
  val bindings: List<BindingSchema> = emptyList(),
  /** The card's Lovelace config, when known. */
  val card: JsonObject? = null,
) {
  companion object {
    /** The schema of a library [design]. */
    fun fromDesign(library: DesignLibrary, design: LibraryDesign): CardSchema {
      val example = design.catalog?.example
      val bindings =
        library.decode(design).bindings.map { binding ->
          BindingSchema(
            name = binding.name,
            type = bindingType(binding.name, binding.type),
            example = example?.bindings?.get(binding.name),
          )
        }
      return CardSchema(
        cardType = design.key.cardType,
        source = "design:${design.id}",
        properties = design.properties,
        slots = slotsOf(bindings.map { it.name }, example?.card),
        bindings = bindings,
        card = example?.card,
      )
    }

    /**
     * The schema of [card] (a Lovelace card config) with [states] for its entities: the bindings
     * the app would push for it. States are optional; without them the examples are placeholders.
     */
    fun fromCard(card: JsonObject, states: Map<String, EntityState> = emptyMap()): CardSchema {
      val type = card["type"]?.jsonPrimitive?.contentOrNull ?: error("card has no type")
      val config = CardConfig(type = type, raw = card)
      val values = CardDesignSlots.slotBindings(config, HaSnapshot(states = states))
      val bindings = buildList {
        values.strings.forEach { (k, v) ->
          add(BindingSchema(k, BindingType.Text, JsonPrimitive(v)))
        }
        values.booleans.forEach { (k, v) ->
          add(BindingSchema(k, BindingType.Bool, JsonPrimitive(v)))
        }
        values.ints.forEach { (k, v) -> add(BindingSchema(k, BindingType.Int, JsonPrimitive(v))) }
        values.floats.forEach { (k, v) ->
          add(BindingSchema(k, BindingType.Float, JsonPrimitive(v).takeIf { v.isFinite() }))
        }
      }
      return CardSchema(
        cardType = type,
        source = "card",
        slots = slotsOf(bindings.map { it.name }, card),
        bindings = bindings.sortedBy { it.name },
        card = card,
      )
    }

    /**
     * Parses a request body: a bare card config, or `{"card": {...}, "states": {...}}` where states
     * map entity id to `{"state": ..., "attributes": {...}}` (or to a bare state string).
     */
    fun parseRequest(body: JsonElement): Pair<JsonObject, Map<String, EntityState>> {
      val obj = body.jsonObject
      val card = (obj["card"] as? JsonObject) ?: obj
      val states =
        (obj["states"] as? JsonObject).orEmpty().mapValues { (id, value) ->
          when (value) {
            is JsonPrimitive -> EntityState(id, value.content)
            is JsonObject ->
              EntityState(
                id,
                value["state"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                (value["attributes"] as? JsonObject) ?: JsonObject(emptyMap()),
              )
            is JsonArray -> error("state for $id must be an object or a string")
          }
        }
      return card to states
    }

    /** The template's value type, refined: an `is_on` int is a boolean to a designer. */
    private fun bindingType(name: String, decoded: String): BindingType =
      when {
        name.endsWith(".is_on") -> BindingType.Bool
        decoded == "text" -> BindingType.Text
        decoded == "int" || decoded == "long" -> BindingType.Int
        decoded == "float" -> BindingType.Float
        decoded == "color" -> BindingType.Color
        decoded == "image" -> BindingType.Image
        else -> BindingType.Text
      }

    private val SLOT = Regex("""^([a-z_]+)\.s(\d+)\.""")

    /** The slots [bindingNames] reference, with each one's entity from [card] when it has one. */
    private fun slotsOf(bindingNames: List<String>, card: JsonObject?): List<SlotSchema> {
      val entities =
        card
          ?.let { c ->
            c["type"]?.jsonPrimitive?.contentOrNull?.let {
              CardDesignSlots.slotEntries(CardConfig(it, c))
            }
          }
          .orEmpty()
      return bindingNames
        .mapNotNull { SLOT.find(it) }
        .map { it.groupValues[1] to it.groupValues[2].toInt() }
        .distinct()
        .sortedBy { it.second }
        .map { (domain, index) ->
          val entry =
            entities.getOrNull(index)?.takeIf { CardDesignSlots.domainOf(it.entityId) == domain }
          SlotSchema(
            index = index,
            slot = "$domain.s$index",
            domain = domain,
            entity = entry?.entityId,
            name = entry?.name,
          )
        }
    }
  }
}

@Serializable
data class SlotSchema(
  val index: Int,
  /** The slot's binding prefix, `<domain>.s<N>`. */
  val slot: String,
  val domain: String,
  /** The example card's entity in this slot, when known. */
  val entity: String? = null,
  val name: String? = null,
)

@Serializable
data class BindingSchema(
  val name: String,
  val type: BindingType,
  val example: JsonPrimitive? = null,
)

@Serializable
enum class BindingType {
  @kotlinx.serialization.SerialName("text") Text,
  @kotlinx.serialization.SerialName("bool") Bool,
  @kotlinx.serialization.SerialName("int") Int,
  @kotlinx.serialization.SerialName("float") Float,
  @kotlinx.serialization.SerialName("color") Color,
  @kotlinx.serialization.SerialName("image") Image,
}
