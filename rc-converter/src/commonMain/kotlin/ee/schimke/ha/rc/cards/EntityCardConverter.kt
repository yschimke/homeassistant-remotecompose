package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardWidthClass
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.components.HaEntityRowData
import ee.schimke.ha.rc.components.HaHeroScale
import ee.schimke.ha.rc.components.HaPresentation
import ee.schimke.ha.rc.components.HaTileData
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.RemoteHaEntityRow
import ee.schimke.ha.rc.components.RemoteHaIconChip
import ee.schimke.ha.rc.components.RemoteHaIconHero
import ee.schimke.ha.rc.components.RemoteHaMeasurement
import ee.schimke.ha.rc.defaultTapActionFor
import ee.schimke.ha.rc.design.DesignedCardConverter
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.haReading
import ee.schimke.ha.rc.icons.HaIconMap
import ee.schimke.ha.rc.icons.withIcons
import ee.schimke.ha.rc.parseHaAction
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * HA `entity` card — single-entity compact row.
 *
 * Its layout is its card design, `entity.uid` ([EntityDesignParts] has the forms and what picks
 * between them): in a fixed canvas the shared icon-+-state ladder (adaptive-card-layouts.md §"Icon
 * + state row/tile"), a narrow cell (`w < 96 dp`) dropping to the icon chip identity tier and a
 *   wider one taking the row, inset as the card is; a launcher cell with a hero scale its hero
 *   forms; a Wear slot the row below [WEAR_SMALL_MAX_HEIGHT_DP] and the wide hero above.
 *
 * Matrix reference shape is `2×1` (see [CardPreviewMatrix_Entity]) — the old `3×1` left the matrix
 * `+1` neighbour ~70 % empty (Q5).
 */
class EntityCardConverter : DesignedCardConverter() {
  override val cardType: String = CardTypes.ENTITY

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 48

  override fun naturalWidthClass(card: CardConfig, snapshot: HaSnapshot): CardWidthClass =
    CardWidthClass.Compact

  /** A plain entity card renders from a card design, like a plain tile ([EntityDesign]). */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? =
    EntityDesign.single(card, snapshot, TEMPLATE_CONFIG_KEYS)

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? =
    EntityDesign.singleTemplate(cardType, properties)

  /**
   * A roomy launcher widget: a numeric reading takes its measurement's expanded presentation (the
   * thermometer or battery grows with the widget, the value is promoted and the range shown), any
   * other entity the icon hero.
   */
  @Composable
  internal fun Hero(
    card: CardConfig,
    snapshot: HaSnapshot,
    scale: HaHeroScale,
    modifier: RemoteModifier,
  ) {
    val entityId = card.raw["entity"]?.jsonPrimitive?.content
    val entity = entityId?.let { snapshot.states[it] }
    // The card's tap action (configured, or the entity's default), as the row's.
    val data = iconChipData(card, snapshot)
    if (entity?.state?.toFloatOrNull() != null) {
      val reading =
        haReading(entityId, entity, nameFor(card, entity, entityId), snapshot = snapshot)
      RemoteHaMeasurement(
        reading,
        HaPresentation.Expanded,
        modifier.fillMaxSize(),
        tapAction = data.tapAction,
      )
    } else {
      RemoteHaIconHero(data, scale, modifier.fillMaxSize())
    }
  }

  @Composable
  internal fun FullRow(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val entityId = card.raw["entity"]?.jsonPrimitive?.content
    val entity = entityId?.let { snapshot.states[it] }
    val tapCfg = card.raw["tap_action"]?.jsonObject
    val tapAction =
      if (tapCfg != null) parseHaAction(tapCfg, entityId) else defaultTapActionFor(entityId)
    val icons = HaIconMap.entityIcons(card.raw["icon"]?.jsonPrimitive?.content, entity)
    RemoteHaEntityRow(
      HaEntityRowData(
        entityId = entityId,
        name = nameFor(card, entity, entityId),
        state = LiveValues.state(entityId, formatState(entity, snapshot)),
        icon = icons.icon,
        accent = HaStateColor.toggleAccentFor(entityId, entity).withIcons(icons),
        tapAction = tapAction,
      ),
      modifier = modifier,
    )
  }

  /**
   * Identity-tier payload — same fields as the full row, shaped as [HaTileData] so the shared
   * [RemoteHaIconChip] can render it.
   */
  @Composable
  internal fun iconChipData(card: CardConfig, snapshot: HaSnapshot): HaTileData {
    val entityId = card.raw["entity"]?.jsonPrimitive?.content
    val entity = entityId?.let { snapshot.states[it] }
    val tapCfg = card.raw["tap_action"]?.jsonObject
    val tapAction =
      if (tapCfg != null) parseHaAction(tapCfg, entityId) else defaultTapActionFor(entityId)
    val icons = HaIconMap.entityIcons(card.raw["icon"]?.jsonPrimitive?.content, entity)
    return HaTileData(
      entityId = entityId,
      name = nameFor(card, entity, entityId),
      state = LiveValues.state(entityId, formatState(entity, snapshot)),
      icon = icons.icon,
      accent = HaStateColor.toggleAccentFor(entityId, entity).withIcons(icons),
      tapAction = tapAction,
    )
  }

  companion object {
    private val TEMPLATE_CONFIG_KEYS = setOf("type", "entity", "name", "icon")

    internal fun nameFor(card: CardConfig, entity: EntityState?, entityId: String?): String =
      card.raw["name"]?.jsonPrimitive?.content
        ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
        ?: entityId
        ?: "(no entity)"
  }
}
