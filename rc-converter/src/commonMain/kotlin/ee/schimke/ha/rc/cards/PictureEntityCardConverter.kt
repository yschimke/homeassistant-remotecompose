package ee.schimke.ha.rc.cards

import androidx.compose.runtime.Composable
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.components.HaPictureEntityData
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.defaultTapActionFor
import ee.schimke.ha.rc.design.DesignedCardConverter
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.icons.HaIconMap
import ee.schimke.ha.rc.icons.withIcons
import ee.schimke.ha.rc.parseHaAction
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `picture-entity` card. RemoteCompose alpha08 can't pull a live camera stream into a `.rc`
 * document, so the renderer paints a tinted placeholder area + name/state bottom bar; tap forwards
 * to the entity's default action (more-info / toggle).
 *
 * Its layout is its card design, `picture-entity.uid` ([PictureDesignParts]): on a dashboard the
 * image keeps HA's `aspect_ratio`; in a launcher cell it fills the cell, the name dropping below
 * 180 dp wide so the state badge alone reads; in a Wear slot it fills the slot with its strip.
 */
class PictureEntityCardConverter : DesignedCardConverter() {
  override val cardType: String = CardTypes.PICTURE_ENTITY

  /** A picture's height follows its width: it keeps a grid column's width. */
  override fun gridKeepsColumnWidth(card: CardConfig, snapshot: HaSnapshot): Boolean = true

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 160

  override fun gridMinCellWidthDp(card: CardConfig, snapshot: HaSnapshot): Int =
    PICTURE_MIN_CELL_WIDTH_DP

  /**
   * A picture-entity card renders from a card design. The image is the slot's picture URL
   * (`CardDesignSlots.pictureUrl`), which playback resolves to the entity's own `entity_picture`;
   * the name and state are bindings. Structure: the entity's [EntityDesign] properties (icon and
   * accent behind or instead of the image), whether it has a picture at all, and which strip lines
   * show.
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? {
    if (!TEMPLATE_CONFIG_KEYS.containsAll(card.raw.keys)) return null
    val entityId = card.raw["entity"]?.jsonPrimitive?.contentOrNull ?: return null
    val entity = snapshot.states[entityId] ?: return null
    var ok = false
    val properties = DesignProperties.build {
      with(EntityDesign) {
        ok =
          entity(
            "",
            entityId,
            card.raw["icon"]?.jsonPrimitive?.contentOrNull,
            snapshot,
            PICTURE_DOMAINS,
          )
      }
      enum(HAS_PICTURE, if (entity.attributes["entity_picture"] != null) 1 else 0)
      enum(SHOW_NAME, if (flag(card, "show_name")) 1 else 0)
      enum(SHOW_STATE, if (flag(card, "show_state")) 1 else 0)
    }
    return properties.takeIf { ok }
  }

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? {
    val base = EntityDesign.templateEntity(properties, "", 0) ?: return null
    val entity =
      if (properties[HAS_PICTURE] == "1") {
        base.copy(
          attributes =
            kotlinx.serialization.json.JsonObject(
              base.attributes +
                ("entity_picture" to
                  kotlinx.serialization.json.JsonPrimitive(
                    CardDesignSlots.pictureUrl(base.entityId)
                  ))
            )
        )
      } else base
    val card =
      CardConfig(
        type = cardType,
        raw =
          buildJsonObject {
            put("type", cardType)
            put("entity", entity.entityId)
            properties[EntityDesign.CARD_ICON]?.let { put("icon", it) }
            if (properties[SHOW_NAME] == "0") put("show_name", false)
            if (properties[SHOW_STATE] == "0") put("show_state", false)
          },
      )
    return DesignTemplateInput(card, HaSnapshot(states = mapOf(entity.entityId to entity)))
  }

  /** What [card] shows: its entity's picture, icon, name and state, and which strip lines show. */
  @Composable
  internal fun pictureEntityData(card: CardConfig, snapshot: HaSnapshot): HaPictureEntityData {
    val entityId = card.raw["entity"]?.jsonPrimitive?.content
    val entity = entityId?.let { snapshot.states[it] }
    val name =
      card.raw["name"]?.jsonPrimitive?.content
        ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
        ?: entityId
        ?: "(no entity)"
    val showName = flag(card, "show_name")
    val showState = flag(card, "show_state")
    val tapCfg = card.raw["tap_action"]?.jsonObject
    val tapAction =
      if (tapCfg != null) parseHaAction(tapCfg, entityId) else defaultTapActionFor(entityId)

    val icons = HaIconMap.entityIcons(card.raw["icon"]?.jsonPrimitive?.content, entity)
    val imageUrl = entity?.attributes?.get("entity_picture")?.jsonPrimitive?.content
    return HaPictureEntityData(
      entityId = entityId,
      name = name,
      state = LiveValues.state(entityId, formatState(entity, snapshot)),
      icon = icons.icon,
      accent = HaStateColor.toggleAccentFor(entityId, entity).withIcons(icons),
      showName = showName,
      showState = showState,
      imageUrl = imageUrl,
      tapAction = tapAction,
    )
  }
}

private val TEMPLATE_CONFIG_KEYS =
  setOf("type", "entity", "name", "icon", "show_name", "show_state")

/** Picture entities: cameras and images, plus the entity domains every templated card supports. */
private val PICTURE_DOMAINS = EntityDesign.DOMAINS + listOf("camera", "image")

private const val HAS_PICTURE = "has_picture"
private const val SHOW_NAME = "show_name"
private const val SHOW_STATE = "show_state"

private fun flag(card: CardConfig, key: String): Boolean =
  card.raw[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
