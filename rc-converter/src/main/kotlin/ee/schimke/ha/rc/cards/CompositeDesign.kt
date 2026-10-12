package ee.schimke.ha.rc.cards

import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.CardRegistry
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.components.CARD_BINDING_PREFIX
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.childCardBindingPrefix
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Card design support for container cards (vertical / horizontal stack, grid): a container's design
 * is its children's designs, side by side.
 *
 * * **Properties**: each child's type and its own properties, under `c<k>.` — a container is
 *   templatable when every child is.
 * * **Slots**: one sequence over all children (`CardDesignSlots.slotEntries` walks `cards:`), so a
 *   child's slots are its own, offset by the slots of the children before it. Child templates are
 *   captured with their own slot ids and renumbered ([offsetSlots]).
 * * **Card-level bindings**: child k's `card.<field>` is `card.c<k>.<field>`
 *   (`ProvideChildCardBindings`), so two headings are two titles.
 * * **Taps**: a tap on a slot runs what the owning child's own playback resolves for it.
 */
internal object CompositeDesign {
  private val registry: CardRegistry
    get() = CardDesignTemplates.defaultTemplateRegistry

  fun children(card: CardConfig): List<CardConfig> =
    (card.raw["cards"] as? JsonArray).orEmpty().mapNotNull { child ->
      val obj = child as? JsonObject ?: return@mapNotNull null
      val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
      CardConfig(type = type, raw = obj)
    }

  fun properties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? {
    val children = children(card)
    if (children.isEmpty() || children.size != (card.raw["cards"] as? JsonArray)?.size) return null
    val shapes = children.map { child ->
      registry.get(child.type)?.designProperties(child, snapshot) ?: return null
    }
    return DesignProperties.build {
      enum(COUNT, children.size)
      children.forEachIndexed { k, child ->
        string("c$k.$TYPE", child.type)
        for ((name, value) in shapes[k].values) string("c$k.$name", value)
      }
    }
  }

  fun template(cardType: String, properties: DesignProperties): DesignTemplateInput? {
    val count = properties[COUNT]?.toIntOrNull() ?: return null
    var offset = 0
    val states = LinkedHashMap<String, EntityState>()
    val history = LinkedHashMap<String, List<ee.schimke.ha.model.HistoryPoint>>()
    val statistics = LinkedHashMap<String, List<ee.schimke.ha.model.StatisticPoint>>()
    val children =
      (0 until count).map { k ->
        val type = properties["c$k.$TYPE"] ?: return null
        val prefix = "c$k."
        val own =
          DesignProperties.parse(
            properties.values.entries
              .filter { it.key.startsWith(prefix) && it.key != "$prefix$TYPE" }
              .joinToString(";") { "${it.key.removePrefix(prefix)}=${it.value}" }
          )
        val input = registry.get(type)?.designTemplate(own) ?: return null
        val slots = CardDesignSlots.slotEntities(input.card)?.size ?: 0
        val rename = { id: String -> offsetSlots(id, offset) }
        for ((id, entity) in input.snapshot.states) {
          states[rename(id)] =
            entity.copy(
              entityId = rename(entity.entityId),
              attributes = offsetSlots(entity.attributes, offset) as JsonObject,
            )
        }
        for ((id, points) in input.snapshot.history) history[rename(id)] = points
        for ((id, points) in input.snapshot.statistics) statistics[rename(id)] = points
        val raw = offsetSlots(input.card.raw, offset) as JsonObject
        offset += slots
        raw
      }
    val card =
      CardConfig(
        type = cardType,
        raw = JsonObject(mapOf("type" to JsonPrimitive(cardType), "cards" to JsonArray(children))),
      )
    return DesignTemplateInput(
      card,
      HaSnapshot(states = states, history = history, statistics = statistics),
    )
  }

  /**
   * Every child's bindings — its slot bindings and its own card-level ones — renumbered into the
   * container's slot sequence and its `card.c<k>` namespace.
   */
  fun bindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings {
    var merged = CardSnapshotBindings(emptyMap(), emptyMap(), emptyMap(), emptyMap())
    var offset = 0
    children(card).forEachIndexed { k, child ->
      val converter = registry.get(child.type)
      val own =
        CardDesignSlots.slotBindings(child, snapshot).let { slots ->
          converter?.designBindings(child, snapshot)?.let { slots + it } ?: slots
        }
      val childPrefix = childCardBindingPrefix(CARD_BINDING_PREFIX, k)
      fun key(name: String) =
        if (name.startsWith("$CARD_BINDING_PREFIX.")) {
          childPrefix + name.removePrefix(CARD_BINDING_PREFIX)
        } else offsetSlots(name, offset)
      merged +=
        CardSnapshotBindings(
          own.strings.mapKeys { key(it.key) },
          own.booleans.mapKeys { key(it.key) },
          own.ints.mapKeys { key(it.key) },
          own.floats.mapKeys { key(it.key) },
        )
      offset += CardDesignSlots.slotEntities(child)?.size ?: 0
    }
    return merged
  }

  /** A tap on container slot [slot]: what the owning child's own playback runs for it. */
  fun action(card: CardConfig, snapshot: HaSnapshot?, slot: Int, action: HaAction): HaAction? {
    // A container can't tell which child a slot-less tap came from.
    if (slot < 0) return null
    var offset = 0
    for (child in children(card)) {
      val slots = CardDesignSlots.slotEntities(child)?.size ?: 0
      if (slot < offset + slots) {
        val local = renameAction(action) { offsetSlots(it, -offset) }
        return CardDesignTemplates.playback(child, ByteArray(0), registry)
          .resolveTap(local, snapshot)
      }
      offset += slots
    }
    return null
  }

  /** [text] with every slot id in it (`light.s3`) moved by [offset] slots. */
  fun offsetSlots(text: String, offset: Int): String =
    if (offset == 0) text
    else SLOT_ID.replace(text) { m -> "${m.groupValues[1]}.s${m.groupValues[2].toInt() + offset}" }

  private fun offsetSlots(element: JsonElement, offset: Int): JsonElement =
    when (element) {
      is JsonObject -> JsonObject(element.mapValues { offsetSlots(it.value, offset) })
      is JsonArray -> JsonArray(element.map { offsetSlots(it, offset) })
      is JsonPrimitive ->
        if (element.isString) JsonPrimitive(offsetSlots(element.content, offset)) else element
    }

  private fun renameAction(action: HaAction, rename: (String) -> String): HaAction =
    when (action) {
      is HaAction.CallService -> action.copy(entityId = action.entityId?.let(rename))
      is HaAction.Toggle -> action.copy(entityId = rename(action.entityId))
      is HaAction.MoreInfo -> action.copy(entityId = rename(action.entityId))
      is HaAction.AlarmKey -> action.copy(entityId = rename(action.entityId))
      is HaAction.AlarmPin -> action.copy(entityId = rename(action.entityId))
      is HaAction.AlarmIntent -> action.copy(entityId = rename(action.entityId))
      is HaAction.Navigate,
      is HaAction.Url,
      HaAction.None -> action
    }

  /** A slot entity id: `<domain>.s<N>`, standalone or inside a slot picture URL. */
  private val SLOT_ID = Regex("""\b([a-z_]+)\.s(\d+)\b""")

  private const val COUNT = "count"
  private const val TYPE = "type"
}
