package ee.schimke.ha.rc.cards

import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.cardDataSignature
import ee.schimke.ha.rc.cardEntityIds
import ee.schimke.ha.rc.components.HaLogbookData
import ee.schimke.ha.rc.components.HaLogbookEntry
import ee.schimke.ha.rc.design.DesignedCardConverter
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.icons.HaIconMap
import kotlin.time.Instant
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
 * entry per configured entity using its current state as the most-recent "message" line, with the
 * entity's last-changed timestamp where available. Hosts that hydrate logbook history at capture
 * time can extend [HaSnapshot] later; for now this gives a representative slot.
 *
 * Its layout is its card design, `logbook.uid` ([BulkDesignParts]): the full card on a dashboard;
 * in a launcher cell its identity below 180 dp wide and the full card above; in a Wear slot its
 * summary line in the Small container and the full card in the Large one.
 */
class LogbookCardConverter : DesignedCardConverter() {
  override val cardType: String = CardTypes.LOGBOOK

  // Baked, non-bindable content (see CardConverter.dataSignature):
  // re-encode when any referenced entity's snapshot data moves.
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String =
    cardDataSignature(cardEntityIds(card), snapshot)

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int {
    val rows = entityIds(card).size.coerceAtLeast(1)
    val title = if (card.raw["title"] != null) 28 else 0
    return title + 24 + 36 * rows
  }

  /**
   * A logbook renders from a card design: per row, the entity's [EntityDesign] properties (its
   * icon); each row's name, message and time are `card.row.<i>.*` bindings.
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

  override fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings =
    CardSnapshotBindings(
      buildMap {
        val data = logbookData(card, snapshot)
        put("card.title", data.title ?: "Logbook")
        put("card.summary", logbookSummary(data))
        data.entries.forEachIndexed { i, entry ->
          put("card.row.$i.name", entry.name)
          put("card.row.$i.message", entry.message)
          put("card.row.$i.when", entry.whenText)
        }
      },
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

private fun formatRelative(instant: Instant?): String {
  if (instant == null) return ""
  return instant.toString().substringBefore('T')
}

internal fun logbookData(card: CardConfig, snapshot: HaSnapshot): HaLogbookData {
  val title = card.raw["title"]?.jsonPrimitive?.content
  val entries =
    entityIds(card).map { entityId ->
      val entity = snapshot.states[entityId]
      val name = entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content ?: entityId
      HaLogbookEntry(
        name = name,
        message = formatState(entity, snapshot),
        whenText = formatRelative(entity?.lastChanged),
        icon = HaIconMap.resolve(null, entity),
      )
    }
  return HaLogbookData(title = title, entries = entries)
}

/**
 * The Wear Small summary line: the latest entry, or that there is none. A `card.summary` binding,
 * so a design template shows the real card's line rather than the template's.
 */
internal fun logbookSummary(data: HaLogbookData): String =
  data.entries.firstOrNull()?.let { "${it.name} ${it.message}" } ?: "No recent activity"
