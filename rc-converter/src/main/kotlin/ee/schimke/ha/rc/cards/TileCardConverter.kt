@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.CardBindingRecipe
import ee.schimke.ha.design.CardBindingRecipes
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.BreakpointAxis
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.CardWidthClass
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.HaNamedColor
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.LocalFixedCanvasAxis
import ee.schimke.ha.rc.LocalWidgetCaptureSizeDp
import ee.schimke.ha.rc.RemoteMinSizeGate
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.cardDataSignature
import ee.schimke.ha.rc.components.HaMeasureKind
import ee.schimke.ha.rc.components.HaReading
import ee.schimke.ha.rc.components.HaTileBadge
import ee.schimke.ha.rc.components.HaTileData
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.RemoteHaIconChip
import ee.schimke.ha.rc.components.RemoteHaIconHero
import ee.schimke.ha.rc.components.RemoteHaTile
import ee.schimke.ha.rc.components.RemoteHaTileCompact
import ee.schimke.ha.rc.components.RemoteHaWideHero
import ee.schimke.ha.rc.components.TileTwoLineMinHeightDp
import ee.schimke.ha.rc.components.haNeutralAccent
import ee.schimke.ha.rc.components.tileFeatureRows
import ee.schimke.ha.rc.components.tileNaturalHeightDp
import ee.schimke.ha.rc.defaultTapActionFor
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.icons.HaIconMap
import ee.schimke.ha.rc.icons.withIcons
import ee.schimke.ha.rc.parseHaAction
import kotlinx.serialization.json.contentOrNull
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
    // The unit system its temperatures are written in is baked too.
    return cardDataSignature(setOf(entityId), snapshot) + "|" + snapshot.temperatureUnit.orEmpty()
  }

  override fun naturalWidthClass(card: CardConfig, snapshot: HaSnapshot): CardWidthClass =
    CardWidthClass.Compact

  /** Icon plus a name and state line that still read: HA's own tile minimum is about this. */
  override fun gridMinCellWidthDp(card: CardConfig, snapshot: HaSnapshot): Int = 140

  /**
   * A plain tile (`entity` plus optional `name` / `icon`) renders from a card design, with the
   * [EntityDesign] properties of its entity. The name and the state are bindings. A sensor whose
   * reading draws a meter glyph adds its unit ([EntityDesign.UNIT]): with `device_class` it picks
   * the glyph's kind and scale (°C or °F), so the template bakes the same reading the converter
   * would. Both are attributes, never the state, so a value never changes the template.
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? =
    EntityDesign.single(card, snapshot, TEMPLATE_CONFIG_KEYS) {
      val entityId = entityIdOf(card)
      val entity = entityId?.let { snapshot.states[it] }
      if (entityId != null && entity != null && measureKind(entityId, entity) != null) {
        string(EntityDesign.UNIT, entity.unit())
      }
    }

  /**
   * A template that draws a meter glyph carries [CardBindingRecipes.TILE], so a non-numeric slot
   * state rests the glyph at 0 instead of NaN. Every other tile template stays recipe-free.
   */
  override fun designRecipe(properties: DesignProperties): CardBindingRecipe? {
    val slot = designTemplate(properties)?.snapshot?.states?.values?.firstOrNull() ?: return null
    return CardBindingRecipes.TILE.takeIf { measureKind(slot.entityId, slot) != null }
  }

  /** The recipe the card's own template carries ([designRecipe]), evaluated for [snapshot]. */
  override fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings? =
    designProperties(card, snapshot)?.let { designRecipe(it) }?.evaluate(card, snapshot)

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
                // The Large slot: the icon big beside the name over a large state, not a
                // phone tile's row of text in the middle of the slot. Wear tiers drop the
                // features first.
                else ->
                  RemoteHaWideHero(
                    tileData(card, snapshot).copy(features = emptyList()),
                    WearHeroScale,
                    RemoteModifier.fillMaxSize(),
                    live = false,
                  )
              }
            }
          BreakpointAxis.Width -> {
            val hero = captureHeroScale()
            if (hero != null && features(card, snapshot).isEmpty())
              RemoteHaIconHero(tileData(card, snapshot), hero, modifier.fillMaxSize())
            else if (captureRowScale() != null && features(card, snapshot).isEmpty())
              RemoteHaWideHero(
                tileData(card, snapshot),
                captureRowScale()!!,
                modifier.fillMaxSize(),
              )
            else if (features(card, snapshot).isNotEmpty())
              FeaturesGate(tileData(card, snapshot), modifier)
            else
              RemoteSizeBreakpoint(
                thresholdsDp = intArrayOf(96),
                modifier = modifier.fillMaxSize(),
              ) { tier ->
                when (tier) {
                  0 ->
                    RemoteHaIconChip(
                      tileData(card, snapshot),
                      RemoteModifier.fillMaxSize(),
                      bindState = LocalWidgetCaptureSizeDp.current != null,
                    )
                  else -> CenteredCell { FullTile(card, snapshot, RemoteModifier.fillMaxWidth()) }
                }
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
    // Features do not make a one-cell widget wide enough for the name/state row.
    // This capture contains only the chip, so its on/off binding cannot collide with a sibling.
    if (LocalWidgetCaptureSizeDp.current?.widthDp?.let { it < 96 } == true) {
      RemoteHaIconChip(data, modifier.fillMaxSize(), bindState = true)
      return
    }
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
    TileContent.features(
      card,
      entityIdOf(card)?.let { snapshot.states[it] },
      snapshot.temperatureUnit,
    )

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
      if (content == null) LiveValues.state(entityId, formatState(entity, snapshot))
      else
        LiveValues.named(
          entityId,
          "state_label",
          TileContent.secondaryText(content, entity, snapshot.temperatureUnit),
        )
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
      features = TileContent.features(card, entity, snapshot.temperatureUnit),
      featuresPosition = TileContent.featuresPosition(card),
      reading = sensorReading(entityId, entity, name, state),
    )
  }

  /**
   * A measurable sensor's [HaReading], so the uber theme can draw its meter glyph in the icon's
   * slot (docs/design/everyday-cards.md §1). The text is the tile's own state binding (never a
   * second `<id>.state`) and the value `<id>.numeric_state`, which the host pushes for any numeric
   * state: path 1, no re-encode.
   *
   * Whether there is a glyph follows the entity's attributes ([measureKind]), never its state: a
   * tile captured while its sensor is unavailable — or a card design's placeholder, whose state is
   * `off` — still bakes the glyph, seeded with [meterSeed] until a numeric value is pushed.
   */
  private fun sensorReading(
    entityId: String?,
    entity: EntityState?,
    name: String,
    text: RemoteString,
  ): HaReading? {
    if (entityId == null || entity == null) return null
    val kind = measureKind(entityId, entity) ?: return null
    val unit = entity.unit()
    val range = kind.defaultRange(unit)
    return HaReading(
      entityId = entityId,
      name = name,
      text = text,
      value = LiveValues.numericState(entityId, meterSeed(entity)),
      unit = unit,
      min = range.start,
      max = range.endInclusive,
      kind = kind,
    )
  }

  /**
   * The value a metered tile's glyph is captured with: the state's number, else 0 — the same
   * fallback [CardBindingRecipes.TILE] pushes to a design template's slot, so a tile reads the same
   * from either path while its sensor is unavailable.
   */
  internal fun meterSeed(entity: EntityState): Float =
    entity.state.toFloatOrNull()?.takeIf { it.isFinite() } ?: 0f

  /**
   * The meter kind a sensor tile draws, from `device_class` and the unit; null outside `sensor`,
   * for kinds with no meaningful scale (an energy total) and for an unclassified reading.
   */
  private fun measureKind(entityId: String, entity: EntityState): HaMeasureKind? {
    if (!entityId.startsWith("sensor.")) return null
    val deviceClass = entity.attributes["device_class"]?.jsonPrimitive?.contentOrNull
    if (deviceClass == "energy") return null
    return HaMeasureKind.of(deviceClass, entity.unit()).takeIf { it != HaMeasureKind.Generic }
  }

  private fun EntityState.unit(): String? =
    attributes["unit_of_measurement"]?.jsonPrimitive?.contentOrNull
}
