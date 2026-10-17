@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.BreakpointAxis
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.CardWidthClass
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.HaNamedColor
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.LocalFixedCanvasAxis
import ee.schimke.ha.rc.RemoteMinSizeGate
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.cardDataSignature
import ee.schimke.ha.rc.components.HaTileBadge
import ee.schimke.ha.rc.components.HaTileData
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.RemoteHaIconChip
import ee.schimke.ha.rc.components.RemoteHaTile
import ee.schimke.ha.rc.components.RemoteHaTileCompact
import ee.schimke.ha.rc.components.TileTwoLineMinHeightDp
import ee.schimke.ha.rc.components.haNeutralAccent
import ee.schimke.ha.rc.components.tileFeatureRows
import ee.schimke.ha.rc.components.tileNaturalHeightDp
import ee.schimke.ha.rc.defaultTapActionFor
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.icons.HaIconMap
import ee.schimke.ha.rc.icons.withIcons
import ee.schimke.ha.rc.parseHaAction
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `tile` card — builds [HaTileData] from config + snapshot.
 *
 * In [CardSizeMode.Wrap] the card always renders the full HA-style tile. In [CardSizeMode.Fixed]
 * (launcher / wear widgets) the converter wraps its output in a [RemoteSizeBreakpoint] icon-+-state
 * ladder (adaptive-card-layouts.md §"Icon + state row/tile"):
 *
 * * **Narrow** (`w < 96 dp`, e.g. a `1×1` launcher cell) → [RemoteHaIconChip], the tinted state
 *   icon centred over a single state line. Keeps the icon identity at the smallest size instead of
 *   dropping to a bare text chip (Principle 1).
 * * **Full** (otherwise) → the HA-reference [RemoteHaTile], wrapped in [CenteredCell] so a tall
 *   cell (`3×2`) centres the row rather than gluing it to the top over a blank half (Principles
 *   7–8).
 *
 * On a Wear slot ([LocalFixedCanvasAxis] is [BreakpointAxis.Height]) every container is wide enough
 * for the full tile, but the small one (a ~44dp content box) is shorter than the name-over-state
 * stack needs ([TileTwoLineMinHeightDp]). The ladder there gates on height instead: below it the
 * tile takes [RemoteHaTileCompact] (the name a step smaller over the body-sized state, tighter
 * padding); at or above it, the full tile.
 *
 * Beyond icon, name and state the tile follows HA's `hui-tile-card` ([TileContent]):
 * * the secondary line honours `state_content`, defaulting by domain (a climate tile reads "Heat ·
 *   22.8 °C");
 * * a climate tile carries the `hvac_action` badge (a flame while heating, a neutral clock while
 *   idle);
 * * `color:` replaces the active state colour with an HA named colour or hex; the inactive look
 *   stays neutral;
 * * `features:` draw under the tile (`target-temperature`, `climate-hvac-modes` and the other
 *   climate mode selects) on the dashboard, and in a Fixed-mode cell only when it has the room
 *   ([RemoteMinSizeGate]); compact and Wear tiers drop them first.
 *
 * Those are baked (formatted labels, the action payloads' stepped set points), so such a tile
 * re-encodes on data changes ([dataSignature]); a plain tile keeps the `<id>.state` binding.
 */
class TileCardConverter : CardConverter {
  companion object {
    /** Domains whose plain tiles render from card designs. */
    val TEMPLATE_DOMAINS: List<String> = EntityDesign.DOMAINS

    /** Config keys a templated tile may carry; anything else renders through the converter. */
    private val TEMPLATE_CONFIG_KEYS = setOf("type", "entity", "name", "icon")

    const val DOMAIN = EntityDesign.DOMAIN

    /** The narrowest launcher cell that shows a tile's features (the full tile's own minimum). */
    private const val FeaturesMinWidthDp = 96
  }

  override val cardType: String = CardTypes.TILE

  /**
   * Two [HaType.Body][ee.schimke.ha.rc.components.HaType.Body] lines (name over state, ~20dp each)
   * inside the tile's 8dp vertical padding. A host that fixes the canvas from this number (the
   * monitoring service, a launcher widget with no reported height) must fit the state line too.
   */
  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int {
    val features = features(card, snapshot)
    if (features.isEmpty()) return TileTwoLineMinHeightDp
    return tileNaturalHeightDp(tileFeatureRows(features.size, TileContent.featuresPosition(card)))
  }

  /**
   * A tile that bakes its secondary line, badge or features re-encodes when its entity changes; a
   * plain tile's state is the `<id>.state` binding.
   */
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String? {
    val entityId = entityIdOf(card) ?: return null
    if (!TileContent.bakes(card, entityId, snapshot.states[entityId])) return null
    return cardDataSignature(setOf(entityId), snapshot)
  }

  override fun naturalWidthClass(card: CardConfig, snapshot: HaSnapshot): CardWidthClass =
    CardWidthClass.Compact

  /** Icon plus a name and state line that still read: HA's own tile minimum is about this. */
  override fun gridMinCellWidthDp(card: CardConfig, snapshot: HaSnapshot): Int = 140

  /**
   * A plain tile (`entity` plus optional `name` / `icon`) renders from a card design, with the
   * [EntityDesign] properties of its entity. The name and the state are bindings.
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? =
    EntityDesign.single(card, snapshot, TEMPLATE_CONFIG_KEYS)

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? =
    EntityDesign.singleTemplate(cardType, properties)

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    when (LocalCardSizeMode.current) {
      CardSizeMode.Wrap -> FullTile(card, snapshot, modifier)
      CardSizeMode.Fixed ->
        when (LocalFixedCanvasAxis.current) {
          // A Wear slot: every container passes the width gate, and the small one is too short
          // for the full name-over-state stack, so it takes the compact stack there.
          BreakpointAxis.Height ->
            RemoteSizeBreakpoint(
              thresholdsDp = intArrayOf(TileTwoLineMinHeightDp),
              axis = BreakpointAxis.Height,
              modifier = modifier.fillMaxSize(),
            ) { tier ->
              when (tier) {
                0 ->
                  CenteredCell {
                    RemoteHaTileCompact(tileData(card, snapshot), RemoteModifier.fillMaxWidth())
                  }
                else ->
                  CenteredCell {
                    // Wear tiers drop the features first.
                    RemoteHaTile(
                      tileData(card, snapshot).copy(features = emptyList()),
                      RemoteModifier.fillMaxWidth(),
                    )
                  }
              }
            }
          BreakpointAxis.Width ->
            if (features(card, snapshot).isNotEmpty())
              FeaturesGate(tileData(card, snapshot), modifier)
            else
              RemoteSizeBreakpoint(
                thresholdsDp = intArrayOf(96),
                modifier = modifier.fillMaxSize(),
              ) { tier ->
                when (tier) {
                  0 -> RemoteHaIconChip(tileData(card, snapshot), RemoteModifier.fillMaxSize())
                  else -> CenteredCell { FullTile(card, snapshot, RemoteModifier.fillMaxWidth()) }
                }
              }
        }
    }
  }

  @Composable
  private fun FullTile(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    RemoteHaTile(tileData(card, snapshot), modifier = modifier)
  }

  /**
   * A launcher cell for a tile with features: one 2-D gate (alpha010 plays a single gate, #224). A
   * cell with room for every feature row shows the full tile with its features; anything smaller
   * shows the compact tile — icon, name and state, no features. The compact branch is the
   * state-layout sibling, so it resolves its colours at encode time and draws no clipped chip (see
   * [RemoteHaTileCompact]).
   */
  @Composable
  private fun FeaturesGate(data: HaTileData, modifier: RemoteModifier) {
    val rows = tileFeatureRows(data.features.size, data.featuresPosition)
    RemoteMinSizeGate(
      minWidthDp = FeaturesMinWidthDp,
      minHeightDp = tileNaturalHeightDp(rows),
      modifier = modifier.fillMaxSize(),
    ) { roomy ->
      if (roomy) CenteredCell { RemoteHaTile(data, RemoteModifier.fillMaxWidth()) }
      else CenteredCell { RemoteHaTileCompact(data, RemoteModifier.fillMaxWidth()) }
    }
  }

  private fun entityIdOf(card: CardConfig): String? = card.raw["entity"]?.jsonPrimitive?.content

  private fun features(card: CardConfig, snapshot: HaSnapshot) =
    TileContent.features(card, entityIdOf(card)?.let { snapshot.states[it] })

  @Composable
  private fun tileData(card: CardConfig, snapshot: HaSnapshot): HaTileData {
    val entityId = entityIdOf(card)
    val entity = entityId?.let { snapshot.states[it] }
    val name =
      card.raw["name"]?.jsonPrimitive?.content
        ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
        ?: entityId
        ?: "(no entity)"
    val tapCfg = card.raw["tap_action"]?.jsonObject
    val tapAction =
      if (tapCfg != null) parseHaAction(tapCfg, entityId) else defaultTapActionFor(entityId)
    val icons = HaIconMap.entityIcons(card.raw["icon"]?.jsonPrimitive?.content, entity)

    // A baked secondary line never goes under `<id>.state`, which the host pushes `formatState`
    // to (live-card-updates.md); such a tile re-encodes instead.
    val content = TileContent.stateContent(card, entityId)
    val state =
      if (content == null) LiveValues.state(entityId, formatState(entity))
      else LiveValues.named(entityId, "state_label", TileContent.secondaryText(content, entity))
    // HA: a configured colour is the active colour; inactive stays the neutral look.
    val stateAccent = HaStateColor.toggleAccentFor(entityId, entity)
    val accent =
      HaNamedColor.resolve(card.raw["color"]?.jsonPrimitive?.content)?.let {
        stateAccent.copy(activeAccent = it.rc, inactiveAccent = haNeutralAccent())
      } ?: stateAccent

    return HaTileData(
      entityId = entityId,
      name = name,
      state = state,
      icon = icons.icon,
      accent = accent.withIcons(icons),
      tapAction = tapAction,
      badge = TileContent.badge(entity)?.let { HaTileBadge(it.icon, it.color, it.description) },
      features = TileContent.features(card, entity),
      featuresPosition = TileContent.featuresPosition(card),
    )
  }
}
