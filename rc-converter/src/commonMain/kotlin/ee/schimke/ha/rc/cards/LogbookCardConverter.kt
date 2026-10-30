package ee.schimke.ha.rc.cards

import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaLiveBindings
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.components.HaLogbookData
import ee.schimke.ha.rc.components.HaLogbookEntry
import ee.schimke.ha.rc.design.DesignedCardConverter
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.icons.HaIconMap
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `logbook` card. The dashboard JSON only carries the entity ids — real logbook data lives behind
 * the `/api/logbook` REST endpoint, which the `.rc` document can't fetch at playback. We render one
 * entry per configured entity: its current state as the most-recent "message" line and when it last
 * changed.
 *
 * Every row follows its entity through named bindings alone (live-card-updates.md, path 1): the
 * message is `<id>.state`, and the time is `<id>.last_changed`, Home Assistant's own timestamp,
 * which the document shows relative to the player's clock (`relativeTimeText`). Nothing re-encodes
 * as the entities change or the clock moves.
 *
 * Its layout is its card design, `logbook.uid` ([BulkDesignParts]): the full card on a dashboard;
 * in a launcher cell its identity below 180 dp wide and the full card above; in a Wear slot its
 * summary line in the Small container and the full card in the Large one.
 */
class LogbookCardConverter : DesignedCardConverter() {
  override val cardType: String = CardTypes.LOGBOOK

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int {
    val rows = entityIds(card).size.coerceAtLeast(1)
    val title = if (card.raw["title"] != null) 28 else 0
    return title + 24 + 36 * rows
  }

  /**
   * A logbook renders from a card design: per row, the entity's [EntityDesign] properties (its
   * icon). Each row's name, message and time are its slot's own bindings (`<slot>.name`,
   * `<slot>.state`, `<slot>.last_changed`).
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? {
    if (!setOf("type", "title", "entities", "hours_to_show").containsAll(card.raw.keys)) return null
    val ids = entityIds(card)
    if (ids.isEmpty()) return null
    var ok = true
    val properties = DesignProperties.build {
      enum("has_title", if (card.raw["title"] != null) 1 else 0)
      enum(EntityDesign.COUNT, ids.size)
      ids.forEachIndexed { i, id ->
        with(EntityDesign) { if (!entity("e$i.", id, null, snapshot)) ok = false }
      }
    }
    return properties.takeIf { ok }
  }

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? {
    val count = properties[EntityDesign.COUNT]?.toIntOrNull() ?: return null
    val entities =
      (0 until count).map { i -> EntityDesign.templateEntity(properties, "e$i.", i) ?: return null }
    val card =
      CardConfig(
        type = cardType,
        raw =
          buildJsonObject {
            put("type", cardType)
            if (properties["has_title"] == "1") put("title", "Title")
            putJsonArray("entities") { entities.forEach { add(it.entityId) } }
          },
      )
    return DesignTemplateInput(card, HaSnapshot(states = entities.associateBy { it.entityId }))
  }

  /** The title, `Logbook` when the card has none, as the Wear summary line's heading shows it. */
  override fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings =
    CardSnapshotBindings(
      mapOf("card.title" to (logbookData(card, snapshot).title ?: "Logbook")),
      emptyMap(),
      emptyMap(),
      emptyMap(),
    )
}

private fun entityIds(card: CardConfig): List<String> {
  val arr: JsonArray = card.raw["entities"]?.jsonArray ?: return emptyList()
  return arr.mapNotNull { el ->
    when (el) {
      is JsonObject -> el["entity"]?.jsonPrimitive?.content
      else -> el.jsonPrimitive.content
    }
  }
}

/** The card's rows; times are read in [zone], the display zone the host pushes offsets in. */
internal fun logbookData(
  card: CardConfig,
  snapshot: HaSnapshot,
  zone: TimeZone = TimeZone.UTC,
): HaLogbookData {
  val title = card.raw["title"]?.jsonPrimitive?.content
  val entries =
    entityIds(card).map { entityId ->
      val entity = snapshot.states[entityId]
      val name = entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content ?: entityId
      HaLogbookEntry(
        name = name,
        message = formatState(entity, snapshot),
        whenText = "",
        icon = HaIconMap.resolve(null, entity),
        entityId = entityId,
        lastChanged = entity?.let(HaLiveBindings::lastChanged) ?: HaLiveBindings.NO_TIME,
        lastChangedOffset = entity?.let { HaLiveBindings.lastChangedOffset(it, zone) } ?: 0,
      )
    }
  return HaLogbookData(title = title, entries = entries)
}
