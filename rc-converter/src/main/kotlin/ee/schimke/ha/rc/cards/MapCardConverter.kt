@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shapes.RemoteCircleShape
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.cardDataSignature
import ee.schimke.ha.rc.cardEntityIds
import ee.schimke.ha.rc.components.HaType
import ee.schimke.ha.rc.components.cardChrome
import ee.schimke.ha.rc.components.cardText
import ee.schimke.ha.rc.components.cardTitle
import ee.schimke.ha.rc.components.currentRemoteHaTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * HA `map` card — list of entities with optional locations.
 *
 * The "real" map card uses Leaflet to render a slippy-map background. We can't ship tiles in a
 * `.rc` document, so this converter renders a flat card chrome with each tracked entity as a
 * labelled dot. It preserves the slot the dashboard expects without faking map tiles.
 *
 * Reads `entities:` (string or `{ entity }`); falls back to the title heuristic from other cards.
 *
 * Each row is the entity's name over its position (`lat, lon`, or its state when it has no
 * coordinates), both single-line and ellipsised so a long name or position never squeezes the other
 * into a sliver. A position HA withholds — `<redacted>` coordinates — has no second line at all:
 * HA's own map shows nothing for an entity it can't place, and a literal `<redacted>, <redacted>`
 * reads as a bug.
 */
class MapCardConverter : CardConverter {
  override val cardType: String = CardTypes.MAP

  // Baked, non-bindable content (see CardConverter.dataSignature):
  // re-encode when any referenced entity's snapshot data moves.
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String =
    cardDataSignature(cardEntityIds(card), snapshot)

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int {
    val entities = entityIds(card)
    val title = if (card.raw["title"] != null) 28 else 0
    if (entities.isEmpty()) return title + 24 + 28
    // A name-only row is one body line; a row with a position adds a label line under it.
    val rows = entities.sumOf { if (rowText(it, snapshot).second == null) 22 else 40 }
    return title + 24 + rows
  }

  /**
   * A map renders from a card design: per row, the entity's domain (its slot) and whether it shows
   * a position line; the title and each row's name and position are bindings (`card.row.<i>.*`).
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? {
    if (
      !setOf("type", "title", "entities", "default_zoom", "hours_to_show")
        .containsAll(card.raw.keys)
    )
      return null
    val ids = entityIds(card)
    if (ids.size != (card.raw["entities"] as? JsonArray)?.size) return null
    return DesignProperties.build {
      enum("has_title", if (card.raw["title"] != null) 1 else 0)
      enum("count", ids.size)
      ids.forEachIndexed { i, id ->
        string("e$i.domain", CardDesignSlots.domainOf(id))
        enum("e$i.where", if (rowText(id, snapshot).second == null) 0 else 1)
      }
    }
  }

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? {
    val count = properties["count"]?.toIntOrNull() ?: return null
    val slots =
      (0 until count).map { i ->
        CardDesignSlots.slotId(properties["e$i.domain"] ?: return null, i)
      }
    val card =
      CardConfig(
        type = cardType,
        raw =
          kotlinx.serialization.json.buildJsonObject {
            put("type", JsonPrimitive(cardType))
            if (properties["has_title"] == "1") put("title", JsonPrimitive("Title"))
            put("entities", JsonArray(slots.map { JsonPrimitive(it) }))
          },
      )
    // A row whose position is withheld draws no position line: give its template entity the same
    // withheld coordinates, so the template lays the row out as the direct render does.
    val states =
      slots
        .filterIndexed { i, _ -> properties["e$i.where"] == "0" }
        .associateWith { slot ->
          CardDesignSlots.templateEntity(
            slot,
            placeholderState = "unknown",
            attributes = mapOf("latitude" to REDACTED, "longitude" to REDACTED),
          )
        }
    return DesignTemplateInput(card, HaSnapshot(states = states))
  }

  override fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings =
    CardSnapshotBindings(
      buildMap {
        entityIds(card).forEachIndexed { i, id ->
          val (name, where) = rowText(id, snapshot)
          put("card.row.$i.name", name)
          if (where != null) put("card.row.$i.where", where)
        }
      },
      emptyMap(),
      emptyMap(),
      emptyMap(),
    )

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val theme = currentRemoteHaTheme()
    val title = card.raw["title"]?.jsonPrimitive?.content
    val entities = entityIds(card)

    RemoteBox(
      modifier =
        modifier
          .fillMaxWidth()
          .then(cardChrome(theme.cardBackground, theme.divider))
          .padding(horizontal = 12.rdp, vertical = 10.rdp)
    ) {
      RemoteColumn(
        modifier = RemoteModifier.fillMaxWidth(),
        verticalArrangement = RemoteArrangement.spacedBy(6.rdp),
      ) {
        if (title != null) {
          RemoteText(
            text = cardTitle(title),
            color = theme.primaryText,
            fontSize = HaType.Title,
            fontWeight = FontWeight.Medium,
            style = RemoteTextStyle.Default,
          )
        }
        if (entities.isEmpty()) {
          RemoteText(
            text = "No entities".rs,
            color = theme.secondaryText,
            fontSize = HaType.Body,
            style = RemoteTextStyle.Default,
          )
        } else {
          entities.forEachIndexed { i, id -> EntityRow(i, id, snapshot) }
        }
      }
    }
  }

  @Composable
  private fun EntityRow(index: Int, entityId: String, snapshot: HaSnapshot) {
    val theme = currentRemoteHaTheme()
    val (name, where) = rowText(entityId, snapshot)

    RemoteRow(
      modifier = RemoteModifier.fillMaxWidth(),
      verticalAlignment = RemoteAlignment.CenterVertically,
    ) {
      RemoteBox(
        modifier =
          RemoteModifier.size(8.rdp).clip(RemoteCircleShape).background(theme.placeholderAccent)
      )
      // The text column takes the rest of the row. The dot-to-text gap is the column's own start
      // padding, not a `spacedBy` arrangement: the RemoteCompose row doesn't subtract `spacedBy`
      // gaps from the weighted share, so the column would run 8 dp past the card edge.
      RemoteColumn(modifier = RemoteModifier.weight(1f).padding(start = 8.rdp)) {
        RemoteText(
          text = cardText("row.$index.name", name),
          color = theme.primaryText,
          fontSize = HaType.Body,
          style = RemoteTextStyle.Default,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (where != null) {
          RemoteText(
            text = cardText("row.$index.where", where),
            color = theme.secondaryText,
            fontSize = HaType.Label,
            style = RemoteTextStyle.Default,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

private fun entityIds(card: CardConfig): List<String> {
  val arr: JsonArray = card.raw["entities"]?.jsonArray ?: return emptyList()
  return arr.mapNotNull { el ->
    when (el) {
      is JsonPrimitive -> el.content
      is JsonObject -> el["entity"]?.jsonPrimitive?.content
      else -> null
    }
  }
}

/** What HA (and a redacted fixture) puts in a coordinate it withholds. */
private const val REDACTED = "<redacted>"

/**
 * A row's name and position text for [entityId]. The position is `lat, lon`, or the entity's state
 * when it has no coordinates, or null when it has coordinates that aren't numbers — HA's
 * `<redacted>` — so the row shows no position rather than placeholder text.
 */
private fun rowText(entityId: String, snapshot: HaSnapshot): Pair<String, String?> {
  val state = snapshot.states[entityId]
  val name = state?.attributes?.get("friendly_name")?.jsonPrimitive?.content ?: entityId
  val lat = state?.attributes?.get("latitude")?.jsonPrimitive?.content
  val lon = state?.attributes?.get("longitude")?.jsonPrimitive?.content
  val coords =
    when {
      lat == null || lon == null -> state?.state ?: "—"
      lat.toDoubleOrNull() == null || lon.toDoubleOrNull() == null -> null
      else -> "$lat, $lon"
    }
  return name to coords
}
