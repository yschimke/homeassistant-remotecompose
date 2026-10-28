package ee.schimke.ha.rc.cards.shutter

import ee.schimke.ha.design.CardBindingRecipe
import ee.schimke.ha.design.CardBindingRecipes
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.components.HaAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The shutter cards' rows and card designs. A card's **shape** is what its document's structure
 * depends on, never the doors' states: how many rows, whether it has a title, and per row whether
 * its name shows, which buttons the config hides in which states, whether it draws a garage door,
 * and whether the integration reports `assumed_state`. Everything else is a binding ([CoverLive]),
 * so one template plays every state of every door of that shape.
 *
 * Rows are prefixed `e<N>.` by slot.
 */
internal object CoverDesign {
  const val COUNT = "count"
  const val TITLE = "title"
  const val NAME_TOP = "name_top"
  const val ASSUMED = "assumed"
  const val GARAGE = "garage"
  const val HIDE_OPEN = "hide_open"
  const val HIDE_STOP = "hide_stop"
  const val HIDE_CLOSE = "hide_close"

  /** Card keys a templated shutter card may carry: anything else it draws, a template can't. */
  private val CARD_KEYS = setOf("type", "title", "entity", "entities", "name", "name_position")

  /** Row keys, and the single-entity card's own row keys. */
  private val ROW_KEYS =
    setOf(
      "entity",
      "name",
      "name_position",
      "button_up_hide_states",
      "button_stop_hide_states",
      "button_down_hide_states",
    )

  /** The card's rows, as config objects: `entities:` (ids or objects), or the card itself. */
  fun rowsOf(card: CardConfig): List<JsonObject> {
    val list = card.raw["entities"] as? JsonArray
    if (list != null) {
      return list.mapNotNull { el ->
        when (el) {
          is JsonObject -> el
          is JsonPrimitive -> el.contentOrNull?.let { JsonObject(mapOf("entity" to el)) }
          else -> null
        }
      }
    }
    return if (card.raw["entity"] != null) listOf(card.raw) else emptyList()
  }

  /**
   * The rows of [card] under [snapshot]. [garage] picks, per entity, a garage door or a window;
   * [hide] reads a row's hide states (the garage card has none); [unknownFraction] is how closed a
   * cover in an unknown state is drawn.
   */
  fun entries(
    card: CardConfig,
    snapshot: HaSnapshot,
    garage: (EntityState?) -> Boolean,
    hide: (JsonObject) -> CoverHideStates,
    unknownFraction: (garage: Boolean) -> Float,
  ): List<CoverEntryData> {
    val cardNamePosition = card.raw.string("name_position")
    return rowsOf(card).map { row ->
      val entityId = row.string("entity")
      val entity = entityId?.let { snapshot.states[it] }
      val isGarage = garage(entity)
      CoverEntryData(
        entityId = entityId,
        entity = entity,
        name =
          row.string("name")
            ?: entity?.attributes?.get("friendly_name")?.let {
              (it as? JsonPrimitive)?.contentOrNull
            }
            ?: entityId
            ?: "(no entity)",
        showNameOnTop = (row.string("name_position") ?: cardNamePosition) != "none",
        hide = hide(row),
        garage = isGarage,
        unknownFraction = unknownFraction(isGarage),
      )
    }
  }

  /**
   * [card]'s shape, or null when a template can't draw it: a config key outside the ones above, a
   * row without a cover, or a cover the snapshot doesn't have yet (the converter draws that).
   */
  fun properties(
    card: CardConfig,
    snapshot: HaSnapshot,
    entries: List<CoverEntryData>,
  ): DesignProperties? {
    if (!CARD_KEYS.containsAll(card.raw.keys)) return null
    val rows = rowsOf(card)
    if (rows.isEmpty() || rows.size != entries.size) return null
    if (rows.any { !ROW_KEYS.containsAll(it.keys - CARD_KEYS) }) return null
    if (entries.any { it.entityId == null || CardDesignSlots.domainOf(it.entityId) != "cover" })
      return null
    if (entries.any { snapshot.states[it.entityId] == null }) return null
    return DesignProperties.build {
      enum(COUNT, entries.size)
      if (card.raw.string("title")?.isNotBlank() == true) enum(TITLE, 1)
      entries.forEachIndexed { i, entry ->
        enum("e$i.$NAME_TOP", if (entry.showNameOnTop) 1 else 0)
        enum("e$i.$GARAGE", if (entry.garage) 1 else 0)
        enum("e$i.$ASSUMED", if (entry.entity?.let(::isAssumedState) == true) 1 else 0)
        string("e$i.$HIDE_OPEN", entry.hide.open.joined())
        string("e$i.$HIDE_STOP", entry.hide.stop.joined())
        string("e$i.$HIDE_CLOSE", entry.hide.close.joined())
      }
    }
  }

  /** The placeholder card and snapshot a template for [properties] is captured from. */
  fun template(cardType: String, properties: DesignProperties): DesignTemplateInput? {
    val count = properties[COUNT]?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val entities =
      List(count) { i ->
        CardDesignSlots.templateEntity(
          CardDesignSlots.slotId("cover", i),
          placeholderState = "closed",
          attributes =
            buildMap {
              if (properties["e$i.$GARAGE"] == "1") put("device_class", "garage")
              if (properties["e$i.$ASSUMED"] == "1") put("assumed_state", "true")
            },
        )
      }
    val card =
      CardConfig(
        type = cardType,
        raw =
          buildJsonObject {
            put("type", cardType)
            if (properties[TITLE] == "1") put("title", "Title")
            putJsonArray("entities") {
              entities.forEachIndexed { i, entity ->
                addJsonObject {
                  put("entity", entity.entityId)
                  if (properties["e$i.$NAME_TOP"] == "0") put("name_position", "none")
                  hideStates("button_up_hide_states", properties["e$i.$HIDE_OPEN"])
                  hideStates("button_stop_hide_states", properties["e$i.$HIDE_STOP"])
                  hideStates("button_down_hide_states", properties["e$i.$HIDE_CLOSE"])
                }
              }
            }
          },
      )
    return DesignTemplateInput(card, HaSnapshot(states = entities.associateBy { it.entityId }))
  }

  /** The recipe a template for [properties] carries: each cover's position and features. */
  fun recipe(properties: DesignProperties): CardBindingRecipe? =
    properties[COUNT]?.toIntOrNull()?.let(CardBindingRecipes::cover)

  /**
   * The action for a tap on a template's button: the same `cover.*` service on the slot's real
   * cover, only while its button works now. A template's greyed button sends `None` and runs
   * nothing; anything else is the slot's default tap.
   */
  fun action(
    entries: List<CoverEntryData>,
    slot: Int,
    templateAction: HaAction,
  ): HaAction? {
    if (templateAction is HaAction.None) return HaAction.None
    val call = templateAction as? HaAction.CallService ?: return null
    if (call.domain != "cover") return null
    val entry = entries.getOrNull(slot) ?: return null
    val controls = coverControls(entry.entity, entry.hide)
    val state =
      when (call.service) {
        "open_cover" -> controls.open
        "stop_cover" -> controls.stop
        "close_cover" -> controls.close
        else -> return null
      }
    return if (state == CoverButtonState.Enabled) coverService(call.service, entry.entityId)
    else HaAction.None
  }

  private fun Set<String>.joined(): String? = takeIf {
    it.isNotEmpty()
  }
    ?.sorted()
    ?.joinToString(",")

  private fun kotlinx.serialization.json.JsonObjectBuilder.hideStates(
    key: String,
    joined: String?,
  ) {
    joined ?: return
    put(key, buildJsonArray { joined.split(',').forEach { add(JsonPrimitive(it)) } })
  }

  private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
