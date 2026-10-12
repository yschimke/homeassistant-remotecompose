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
 * bindings `CardDesignSlots` derives, plus the card-specific ones its converter adds, taken from
 * the closest library template of the same card type).
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
  /** For a card's schema: the library design its card-specific bindings come from. */
  val template: String? = null,
  /** What this schema can't vouch for, in words: missing bindings, approximations. */
  val notes: List<String> = emptyList(),
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
    fun fromCard(
      card: JsonObject,
      states: Map<String, EntityState> = emptyMap(),
      library: DesignLibrary? = null,
    ): CardSchema {
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
      // A converter adds card-specific bindings (a sensor's range, a markdown card's content, …)
      // that only it can compute, and it runs on Android. The library's templates were captured
      // from those converters, so the closest template of the same card type declares them, and
      // its catalog example has a value for each.
      val slotIds = slotsOf(bindings.map { it.name }, card).map { it.slot }
      val template = library?.let { closestTemplate(it, type, slotIds) }
      val known = bindings.map { it.name }.toSet()
      val added =
        if (library == null || template == null) emptyList()
        else {
          val example = template.catalog?.example?.bindings.orEmpty()
          library
            .decode(template)
            .bindings
            .filter { it.name !in known }
            .map { BindingSchema(it.name, bindingType(it.name, it.type), example[it.name]) }
        }
      val all = (bindings + added).sortedBy { it.name }
      return CardSchema(
        cardType = type,
        source = "card",
        slots = slotsOf(all.map { it.name }, card),
        bindings = all,
        card = card,
        template = template?.id,
        notes =
          buildList {
            if (template == null)
              add(
                "No library template for `$type`: only the slot bindings are listed. Values " +
                  "its converter adds (ranges, history, content) are missing."
              )
            else if (added.isNotEmpty())
              add(
                "${added.size} card-specific bindings come from library design ${template.id}, " +
                  "with its example's values."
              )
          },
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

    /**
     * The library design of [cardType] whose slots best match [slotIds]: the same slots in order
     * first, then the most in common. Null when the library has none of that type.
     */
    private fun closestTemplate(
      library: DesignLibrary,
      cardType: String,
      slotIds: List<String>,
    ): LibraryDesign? {
      val candidates = library.designs.filter { it.key.cardType == cardType }
      if (candidates.isEmpty()) return null
      fun slotsOf(design: LibraryDesign) =
        library
          .decode(design)
          .bindings
          .mapNotNull { SLOT.find(it.name)?.value?.removeSuffix(".") }
          .distinct()
          .sortedBy { it.substringAfterLast(".s").toIntOrNull() ?: 0 }
      return candidates.maxBy { design ->
        val slots = slotsOf(design)
        val exact = if (slots == slotIds) 1_000 else 0
        exact + slots.count { it in slotIds } - slots.count { it !in slotIds }
      }
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
