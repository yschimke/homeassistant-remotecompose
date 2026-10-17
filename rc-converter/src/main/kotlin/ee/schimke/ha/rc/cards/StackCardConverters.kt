package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardRegistry
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.HaClock
import ee.schimke.ha.rc.LocalCardRegistry
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.LocalGridSquareCells
import ee.schimke.ha.rc.LocalWidgetCaptureSizeDp
import ee.schimke.ha.rc.ProvideChildCaptureShare
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.SystemHaClock
import ee.schimke.ha.rc.UNSUPPORTED_CARD_TYPE
import ee.schimke.ha.rc.childrenDataSignature
import ee.schimke.ha.rc.components.HORIZONTAL_STACK_GAP_DP
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaGridLayout
import ee.schimke.ha.rc.components.ProvideChildCardBindings
import ee.schimke.ha.rc.components.RemoteAspectRatioBox
import ee.schimke.ha.rc.components.RemoteHaGrid
import ee.schimke.ha.rc.components.RemoteHaHorizontalStack
import ee.schimke.ha.rc.components.RemoteHaVerticalStack
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Layout-only cards. Each unrolls its `cards:` array through `RenderChild`, which dispatches to the
 * registered converter per child.
 */
class VerticalStackCardConverter : CardConverter {
  override val cardType: String = CardTypes.VERTICAL_STACK

  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? =
    CompositeDesign.properties(card, snapshot)

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? =
    CompositeDesign.template(cardType, properties)

  override fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings =
    CompositeDesign.bindings(card, snapshot, SystemHaClock)

  override fun designBindings(
    card: CardConfig,
    snapshot: HaSnapshot,
    clock: HaClock,
  ): CardSnapshotBindings = CompositeDesign.bindings(card, snapshot, clock)

  override fun designAction(
    card: CardConfig,
    snapshot: HaSnapshot?,
    slot: Int,
    templateAction: HaAction,
  ): HaAction? = CompositeDesign.action(card, snapshot, slot, templateAction)

  /** A stack bakes what its children bake: see [childrenDataSignature]. */
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot, registry: CardRegistry) =
    registry.childrenDataSignature(childCards(card), snapshot)

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    RemoteHaVerticalStack(modifier = modifier) { RenderChildren(card, snapshot) }
  }
}

class HorizontalStackCardConverter : CardConverter {
  override val cardType: String = CardTypes.HORIZONTAL_STACK

  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? =
    CompositeDesign.properties(card, snapshot)

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? =
    CompositeDesign.template(cardType, properties)

  override fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings =
    CompositeDesign.bindings(card, snapshot, SystemHaClock)

  override fun designBindings(
    card: CardConfig,
    snapshot: HaSnapshot,
    clock: HaClock,
  ): CardSnapshotBindings = CompositeDesign.bindings(card, snapshot, clock)

  override fun designAction(
    card: CardConfig,
    snapshot: HaSnapshot?,
    slot: Int,
    templateAction: HaAction,
  ): HaAction? = CompositeDesign.action(card, snapshot, slot, templateAction)

  /** A stack bakes what its children bake: see [childrenDataSignature]. */
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot, registry: CardRegistry) =
    registry.childrenDataSignature(childCards(card), snapshot)

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    // HA splits a horizontal stack's width evenly; each child is captured for its own column.
    val children = childCards(card)
    ProvideChildCaptureShare(columns = children.size, gapDp = HORIZONTAL_STACK_GAP_DP) {
      CompositionLocalProvider(LocalGridSquareCells provides false) {
        RemoteHaHorizontalStack(cellCount = children.size, modifier = modifier) { k ->
          ProvideChildCardBindings(k) {
            RenderChild(children[k], snapshot, RemoteModifier.fillMaxWidth())
          }
        }
      }
    }
  }
}

/**
 * HA `grid` card. `columns` (HA default 3) is the **most** cells per row: each child also has a
 * minimum cell width ([CardConverter.gridMinCellWidthDp]), and a grid too narrow for `columns` of
 * them lays out fewer, wider cells, wrapping to more rows — down to one per row (design principle
 * 1: same content, arrangement follows the frame). The document is captured once and played at its
 * host's width, so the per-row count is chosen at playback: a single runtime width gate
 * ([RemoteSizeBreakpoint]) between the two tiers of [HaGridLayout.plan], whose narrow tier is sized
 * for the narrowest width the surface plays at — a dashboard ([CardSizeMode.Wrap]) from
 * [HaGridLayout.NARROW_REFERENCE_DP], a widget ([CardSizeMode.Fixed]) from
 * [HaGridLayout.FIXED_NARROW_REFERENCE_DP]. A launcher capture, re-recorded for each size, lays its
 * rows out for exactly its width instead.
 *
 * `square` (HA default true) makes the cells of button-like children square
 * ([CardConverter.gridSquareCell]) and gives picture-family children a 1:1 default
 * ([LocalGridSquareCells]); `square: false` lets every child keep its own height.
 */
class GridCardConverter : CardConverter {
  override val cardType: String = CardTypes.GRID

  /** The grid's own structure, beside its children's: only the options the config sets. */
  private fun layout(card: CardConfig): Map<String, Int> = buildMap {
    card.raw[COLUMNS]?.let { put(COLUMNS, gridColumns(card)) }
    card.raw[SQUARE]?.let { put(SQUARE, if (gridSquare(card)) 1 else 0) }
  }

  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? =
    CompositeDesign.properties(card, snapshot, layout(card))

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? =
    CompositeDesign.template(
      cardType,
      properties,
      mapOf(COLUMNS to { JsonPrimitive(it) }, SQUARE to { JsonPrimitive(it == 1) }),
    )

  override fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings =
    CompositeDesign.bindings(card, snapshot, SystemHaClock)

  override fun designBindings(
    card: CardConfig,
    snapshot: HaSnapshot,
    clock: HaClock,
  ): CardSnapshotBindings = CompositeDesign.bindings(card, snapshot, clock)

  override fun designAction(
    card: CardConfig,
    snapshot: HaSnapshot?,
    slot: Int,
    templateAction: HaAction,
  ): HaAction? = CompositeDesign.action(card, snapshot, slot, templateAction)

  /** A stack bakes what its children bake: see [childrenDataSignature]. */
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot, registry: CardRegistry) =
    registry.childrenDataSignature(childCards(card), snapshot)

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    // Cells for what the children draw now: a hidden conditional takes no cell, a shown one is
    // sized as the card it shows.
    // Each keeps its authored index for its card-level template bindings.
    val shown =
      childCards(card).mapIndexedNotNull { k, child -> shownCard(child, snapshot)?.let { k to it } }
    val children = shown.map { it.second }
    val bindingIndices = shown.map { it.first }
    val registry = LocalCardRegistry.current
    val converters = children.map { registry.get(it.type) ?: registry.get(UNSUPPORTED_CARD_TYPE) }
    val square = gridSquare(card)
    // Equal-width cells, so the widest minimum sets the grid's.
    val minCellDp =
      children.indices.maxOfOrNull { k ->
        converters[k]?.gridMinCellWidthDp(children[k], snapshot) ?: DEFAULT_MIN_CELL_DP
      } ?: DEFAULT_MIN_CELL_DP
    val squareCells =
      children.indices.map { k ->
        square && converters[k]?.gridSquareCell(children[k], snapshot) == true
      }
    val columns = gridColumns(card)
    // A launcher capture is re-recorded for every size it is shown at, so it lays its rows out for
    // exactly that width. Everything else is captured once and picks a tier at playback, its narrow
    // tier sized for the narrowest width its surface plays at.
    val captureWidthDp = LocalWidgetCaptureSizeDp.current?.widthDp
    val narrowReferenceDp =
      when (LocalCardSizeMode.current) {
        CardSizeMode.Wrap -> HaGridLayout.NARROW_REFERENCE_DP
        CardSizeMode.Fixed -> HaGridLayout.FIXED_NARROW_REFERENCE_DP
      }
    val plan = HaGridLayout.plan(children.size, columns, minCellDp, narrowReferenceDp)

    CompositionLocalProvider(LocalGridSquareCells provides square) {
      val threshold = plan.thresholdDp
      if (captureWidthDp != null) {
        val perRow =
          HaGridLayout.perRowAt(captureWidthDp.toFloat(), children.size, columns, minCellDp)
        val rows = (children.size + perRow - 1) / perRow
        // Each cell is captured for its own share of the widget, so a nested grid or size gate
        // lays out for its cell rather than the whole widget.
        ProvideChildCaptureShare(columns = perRow, rows = rows, gapDp = HaGridLayout.GAP_DP) {
          GridCells(children, bindingIndices, squareCells, snapshot, perRow, modifier)
        }
      } else if (threshold == null) {
        GridCells(children, bindingIndices, squareCells, snapshot, plan.widePerRow, modifier)
      } else {
        RemoteSizeBreakpoint(
          thresholdsDp = intArrayOf(threshold),
          modifier = modifier.fillMaxWidth(),
        ) { tier ->
          val perRow = if (tier > 0) plan.widePerRow else plan.narrowPerRow
          GridCells(
            children,
            bindingIndices,
            squareCells,
            snapshot,
            perRow,
            RemoteModifier.fillMaxWidth(),
          )
        }
      }
    }
  }

  private companion object {
    const val COLUMNS = "columns"
    const val SQUARE = "square"

    /** A child whose converter can't be found: the unsupported placeholder's compact default. */
    const val DEFAULT_MIN_CELL_DP = 120
  }
}

/**
 * [children] in rows of [perRow]; a [squareCells] child fills a square cell. [bindingIndices] is
 * each child's authored index, for its card-level template bindings.
 */
@Composable
private fun GridCells(
  children: List<CardConfig>,
  bindingIndices: List<Int>,
  squareCells: List<Boolean>,
  snapshot: HaSnapshot,
  perRow: Int,
  modifier: RemoteModifier,
) {
  RemoteHaGrid(cellCount = children.size, perRow = perRow, modifier = modifier) { k ->
    ProvideChildCardBindings(bindingIndices[k]) {
      if (squareCells[k]) {
        RemoteAspectRatioBox(1f, RemoteModifier.fillMaxWidth()) {
          RenderChild(children[k], snapshot, RemoteModifier.fillMaxSize())
        }
      } else {
        RenderChild(children[k], snapshot, RemoteModifier.fillMaxWidth())
      }
    }
  }
}

/** HA's `columns`: a positive integer, default [HaGridLayout.DEFAULT_COLUMNS]. */
internal fun gridColumns(card: CardConfig): Int =
  (card.raw["columns"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
    ?: HaGridLayout.DEFAULT_COLUMNS

/** HA's `square`: default true. */
internal fun gridSquare(card: CardConfig): Boolean =
  (card.raw["square"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true

/**
 * Each child, as child k of this card for its card-level template bindings. A stack is not a grid
 * cell, so a stack nested in a square grid doesn't pass the square default on to its children.
 */
@Composable
private fun RenderChildren(card: CardConfig, snapshot: HaSnapshot) {
  CompositionLocalProvider(LocalGridSquareCells provides false) {
    childCards(card).forEachIndexed { k, child ->
      ProvideChildCardBindings(k) { RenderChild(child, snapshot) }
    }
  }
}

private fun childCards(card: CardConfig): List<CardConfig> =
  card.raw["cards"]
    ?.jsonArray
    ?.mapNotNull { it as? JsonObject }
    ?.mapNotNull { obj ->
      val type = obj["type"]?.jsonPrimitive?.content ?: return@mapNotNull null
      CardConfig(type = type, raw = obj)
    } ?: emptyList()
