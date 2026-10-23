package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.BreakpointAxis
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.CardWidthClass
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.LocalFixedCanvasAxis
import ee.schimke.ha.rc.LocalWidgetCaptureSizeDp
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.components.HaEntityRowData
import ee.schimke.ha.rc.components.HaHeroScale
import ee.schimke.ha.rc.components.HaPresentation
import ee.schimke.ha.rc.components.HaSpace
import ee.schimke.ha.rc.components.HaTileData
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.RemoteHaEntityRow
import ee.schimke.ha.rc.components.RemoteHaIconChip
import ee.schimke.ha.rc.components.RemoteHaIconHero
import ee.schimke.ha.rc.components.RemoteHaMeasurement
import ee.schimke.ha.rc.components.RemoteHaWideHero
import ee.schimke.ha.rc.defaultTapActionFor
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
 * In [CardSizeMode.Fixed] the converter runs the shared icon-+-state ladder
 * (adaptive-card-layouts.md §"Icon + state row/tile"): a narrow cell (`w < 96 dp`) drops to the
 * [RemoteHaIconChip] identity tier (tinted icon + state, no name), and the [RemoteHaEntityRow] full
 * tier is wrapped in [CenteredCell] so a tall cell centres the row instead of leaving a blank lower
 * half.
 *
 * Matrix reference shape is `2×1` (see [CardPreviewMatrix_Entity]) — the old `3×1` left the matrix
 * `+1` neighbour ~70 % empty (Q5).
 */
class EntityCardConverter : CardConverter {
  override val cardType: String = CardTypes.ENTITY

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 48

  override fun naturalWidthClass(card: CardConfig, snapshot: HaSnapshot): CardWidthClass =
    CardWidthClass.Compact

  /** A plain entity card renders from a card design, like a plain tile ([EntityDesign]). */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? =
    EntityDesign.single(card, snapshot, TEMPLATE_CONFIG_KEYS)

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? =
    EntityDesign.singleTemplate(cardType, properties)

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    when (LocalCardSizeMode.current) {
      CardSizeMode.Wrap -> FullRow(card, snapshot, modifier)
      CardSizeMode.Fixed -> {
        val hero = captureHeroScale()
        if (hero != null) {
          Hero(card, snapshot, hero, modifier)
          return
        }
        val row = captureRowScale()
        if (row != null) {
          RemoteHaWideHero(iconChipData(card, snapshot), row, modifier.fillMaxSize())
          return
        }
        if (LocalFixedCanvasAxis.current == BreakpointAxis.Height) {
          // A Wear slot: the Small container holds the row, the Large one the icon big beside the
          // name over a large state.
          RemoteSizeBreakpoint(
            thresholdsDp = intArrayOf(WEAR_SMALL_MAX_HEIGHT_DP),
            modifier = modifier.fillMaxSize(),
            axis = BreakpointAxis.Height,
          ) { tier ->
            if (tier == 0)
              FullRow(
                card,
                snapshot,
                RemoteModifier.fillMaxSize().padding(horizontal = HaSpace.L.rdp),
              )
            else
              RemoteHaWideHero(
                iconChipData(card, snapshot),
                WearHeroScale,
                RemoteModifier.fillMaxSize(),
                live = false,
              )
          }
          return
        }
        RemoteSizeBreakpoint(thresholdsDp = intArrayOf(96), modifier = modifier.fillMaxSize()) {
          tier ->
          when (tier) {
            0 ->
              RemoteHaIconChip(
                iconChipData(card, snapshot),
                RemoteModifier.fillMaxSize(),
                bindState = LocalWidgetCaptureSizeDp.current != null,
              )
            // The row fills the cell and self-centres via its
            // own `CenterVertically` (like RemoteHaGaugeWide),
            // so a tall cell isn't top-glued (Principle 8). A
            // `fillMaxWidth` wrap-height row collapses to zero
            // height under a filled-height parent in alpha010,
            // so `fillMaxSize`, not `CenteredCell`.
            // The cell is the card: inset the row as a card's padding would, or its value meets
            // the widget's edge.
            else ->
              FullRow(
                card,
                snapshot,
                RemoteModifier.fillMaxSize().padding(horizontal = HaSpace.L.rdp),
              )
          }
        }
      }
    }
  }

  /**
   * A roomy launcher widget: a numeric reading takes its measurement's expanded presentation (the
   * thermometer or battery grows with the widget, the value is promoted and the range shown), any
   * other entity the icon hero.
   */
  @Composable
  private fun Hero(
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
  private fun FullRow(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
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
  private fun iconChipData(card: CardConfig, snapshot: HaSnapshot): HaTileData {
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
