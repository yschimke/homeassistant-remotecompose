package ee.schimke.ha.rc.cards

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaLiveBindings
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.components.HaPictureGlanceCell
import ee.schimke.ha.rc.components.HaPictureGlanceData
import ee.schimke.ha.rc.components.RemoteHaPictureGlance
import ee.schimke.ha.rc.defaultTapActionFor
import ee.schimke.ha.rc.icons.HaIconMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** `picture-glance` card — image with a row of clickable entity cells. */
class PictureGlanceCardConverter : CardConverter {
  override val cardType: String = CardTypes.PICTURE_GLANCE

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 168

  /**
   * A picture glance renders from a card design: per cell, the entity's [EntityDesign] properties
   * (icon, accent); the title and the cells' names are bindings and each cell's on/off is `is_on`.
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? {
    if (!setOf("type", "title", "image", "entities").containsAll(card.raw.keys)) return null
    val rows = card.raw["entities"] as? JsonArray ?: return null
    if (rows.isEmpty() || rows.any { it is JsonObject && !setOf("entity").containsAll(it.keys) })
      return null
    var ok = true
    val properties = DesignProperties.build {
      enum("has_title", if (card.raw["title"] != null) 1 else 0)
      enum(EntityDesign.COUNT, rows.size)
      rows.forEachIndexed { i, row ->
        val id =
          (row as? JsonObject)?.get("entity")?.jsonPrimitive?.content
            ?: (row as? JsonPrimitive)?.content
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

  /** Cells are named by friendly name only, never a row's `name:`. */
  override fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings =
    CardSnapshotBindings(
      buildMap {
        CardDesignSlots.slotEntities(card).orEmpty().forEachIndexed { i, id ->
          put(
            "${CardDesignSlots.slotId(CardDesignSlots.domainOf(id), i)}.name",
            snapshot.states[id]?.attributes?.get("friendly_name")?.jsonPrimitive?.content ?: id,
          )
        }
      },
      emptyMap(),
      emptyMap(),
      emptyMap(),
    )

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val image = card.raw["image"]?.jsonPrimitive?.content
    val title = card.raw["title"]?.jsonPrimitive?.content

    val ids: List<String> =
      (card.raw["entities"] as? JsonArray)
        ?.mapNotNull { el ->
          when (el) {
            is JsonObject -> el["entity"]?.jsonPrimitive?.content
            else -> el.jsonPrimitive.content
          }
        }
        .orEmpty()

    val cells = ids.map { id ->
      val entity = snapshot.states[id]
      val name = entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content ?: id
      val active = entity != null && HaLiveBindings.isOn(id, entity.state)
      val icons = HaIconMap.entityIcons(null, entity)
      HaPictureGlanceCell(
        entityId = id,
        icon = icons.icon,
        activeIcon = icons.activeIcon,
        accent = HaStateColor.activeFor(entity),
        initiallyActive = active,
        label = name,
        tapAction = defaultTapActionFor(id),
      )
    }

    RemoteHaPictureGlance(
      HaPictureGlanceData(
        title = title,
        captionUrl = image,
        placeholderIcon = Icons.Filled.Image,
        cells = cells,
      ),
      modifier = modifier,
    )
  }
}
