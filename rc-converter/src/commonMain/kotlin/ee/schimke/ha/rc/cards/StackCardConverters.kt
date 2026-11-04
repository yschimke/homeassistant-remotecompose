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
import ee.schimke.ha.rc.BreakpointAxis
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardRegistry
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.HaClock
import ee.schimke.ha.rc.LocalCardRegistry
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.LocalFixedCanvasAxis
import ee.schimke.ha.rc.LocalGridSquareCells
import ee.schimke.ha.rc.LocalInCardContainer
import ee.schimke.ha.rc.LocalWidgetCaptureSizeDp
import ee.schimke.ha.rc.ProvideChildCaptureShare
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.SystemHaClock
import ee.schimke.ha.rc.UNSUPPORTED_CARD_TYPE
import ee.schimke.ha.rc.cardHeightDp
import ee.schimke.ha.rc.childrenDataSignature
import ee.schimke.ha.rc.components.HORIZONTAL_STACK_GAP_DP
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaGridLayout
import ee.schimke.ha.rc.components.ProvideChildCardBindings
import ee.schimke.ha.rc.components.RemoteAspectRatioBox
import ee.schimke.ha.rc.components.RemoteHaGrid
import ee.schimke.ha.rc.components.RemoteHaHorizontalStack
import ee.schimke.ha.rc.components.RemoteHaVerticalStack
import ee.schimke.ha.rc.design.DesignedCardConverter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Layout-only cards. Each unrolls its `cards:` array through `RenderChild`, which dispatches to the
 * registered converter per child.
 */
class VerticalStackCardConverter : DesignedCardConverter() {
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

  /**
   * The container and its children, as every surface draws it (`vertical-stack.uid`,
   * [ContainerDesignParts]).
   */
  @Composable
  internal fun Draw(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val all = childCards(card)
    if (LocalCardSizeMode.current != CardSizeMode.Fixed || all.size < 2) {
      RemoteHaVerticalStack(modifier = modifier) { RenderChildren(all, snapshot) }
      return
    }
    // A fixed canvas (a launcher widget, a Wear slot) can't grow. Show only the children that fit
    // whole: a child cut off at the bottom edge would still take taps in the sliver left showing.
    // Fit what the children draw now: a hidden conditional takes no room or place, and each child
    // keeps its authored index for its card-level template bindings (as the grid does).
    val visible = all.mapIndexedNotNull { k, child -> shownCard(child, snapshot)?.let { k to it } }
    if (visible.isEmpty()) {
      RemoteHaVerticalStack(modifier = modifier) { RenderChildren(all, snapshot) }
      return
    }
    val registry = LocalCardRegistry.current
    val capture = LocalWidgetCaptureSizeDp.current
    val heights = visible.map { (_, shown) ->
      stackChildHeightDp(shown, snapshot, registry, capture?.widthDp, capture?.heightDp)
    }
    val indices = visible.map { it.first }
    when {
      capture != null -> {
        val count = fittingChildren(heights, capture.heightDp)
        RemoteHaVerticalStack(modifier = modifier) {
          RenderChildren(all, snapshot, indices.take(count))
        }
      }
      // A Wear slot's height is only known at playback: one child, or two once both fit.
      LocalFixedCanvasAxis.current == BreakpointAxis.Height && visible.size >= 2 ->
        RemoteSizeBreakpoint(
          thresholdsDp = intArrayOf(heights[0] + VERTICAL_STACK_GAP_DP + heights[1]),
          modifier = modifier,
          axis = BreakpointAxis.Height,
        ) { tier ->
          RemoteHaVerticalStack { RenderChildren(all, snapshot, indices.take(tier + 1)) }
        }
      LocalFixedCanvasAxis.current == BreakpointAxis.Height ->
        RemoteHaVerticalStack(modifier = modifier) {
          RenderChildren(all, snapshot, indices.take(1))
        }
      else -> RemoteHaVerticalStack(modifier = modifier) { RenderChildren(all, snapshot) }
    }
  }
}

/**
 * [card]'s height as a stack child: a nested stack from its own children (a vertical one their sum
 * with its gaps, a horizontal one its tallest), a grid from its rows of cells, and any other card
 * its converter's natural height. Containers don't override `naturalHeightDp`, so its 160dp default
 * would otherwise stand in for every nested container, however short its content.
 */
internal fun stackChildHeightDp(
  card: CardConfig,
  snapshot: HaSnapshot,
  registry: CardRegistry,
  widthDp: Int? = null,
  heightDp: Int? = null,
): Int {
  // A hidden conditional takes no room.
  val children = childCards(card).mapNotNull { shownCard(it, snapshot) }
  return when (card.type) {
    CardTypes.VERTICAL_STACK ->
      children.sumOf { stackChildHeightDp(it, snapshot, registry, widthDp, heightDp) } +
        VERTICAL_STACK_GAP_DP * (children.size - 1).coerceAtLeast(0)
    CardTypes.HORIZONTAL_STACK -> {
      val share = widthDp?.let {
        (it - HORIZONTAL_STACK_GAP_DP * (children.size - 1)) / children.size.coerceAtLeast(1)
      }
      children.maxOfOrNull { stackChildHeightDp(it, snapshot, registry, share, heightDp) } ?: 0
    }
    CardTypes.GRID ->
      widthDp?.let { launcherGridHeightDp(card, children, snapshot, registry, it, heightDp) }
        ?: gridColumns(card).coerceAtLeast(1).let { columns ->
          children.chunked(columns).sumOf { row ->
            row.maxOf { stackChildHeightDp(it, snapshot, registry) }
          }
        }
    else -> registry.cardHeightDp(card, snapshot)
  }
}

/**
 * The height a grid of [children] draws in a launcher widget [widthDp] × [heightDp], by the rules
 * [GridCardConverter.Draw] lays it out with there ([HaGridLayout.fit]): as many cells to a row as
 * the width holds, more to a row when the widget's height can't hold the rows that leaves, each
 * [HaGridLayout.MIN_FIXED_CELL_DP] tall or, for square cells, as tall as it is wide.
 */
private fun launcherGridHeightDp(
  card: CardConfig,
  children: List<CardConfig>,
  snapshot: HaSnapshot,
  registry: CardRegistry,
  widthDp: Int,
  heightDp: Int?,
): Int {
  if (children.isEmpty()) return 0
  val converters = children.map { registry.get(it.type) ?: registry.get(UNSUPPORTED_CARD_TYPE) }
  val minCellDp =
    children.indices.maxOf { k ->
      converters[k]?.gridMinCellWidthDp(children[k], snapshot)
        ?: GridCardConverter.DEFAULT_MIN_CELL_DP
    }
  val square =
    gridSquare(card) &&
      children.indices.any { k -> converters[k]?.gridSquareCell(children[k], snapshot) == true }
  val columns = gridColumns(card)
  val keepColumns =
    children.indices.any { k -> converters[k]?.gridKeepsColumnWidth(children[k], snapshot) == true }
  val planCount = if (keepColumns) maxOf(children.size, columns) else children.size
  val widthPerRow =
    HaGridLayout.perRowAt(widthDp.toFloat(), planCount, columns, minCellDp).coerceAtLeast(1)
  val gap = HaGridLayout.GAP_DP
  val (perRow, shown) =
    heightDp?.let {
      val fit = HaGridLayout.fit(widthDp, it, children.size, widthPerRow, square)
      fit.perRow.coerceAtLeast(1) to fit.shown
    } ?: (widthPerRow to children.size)
  val cellDp =
    if (square) (widthDp - gap * (perRow - 1)) / perRow else HaGridLayout.MIN_FIXED_CELL_DP
  val rows = (shown + perRow - 1) / perRow
  return rows * cellDp + gap * (rows - 1).coerceAtLeast(0)
}

/**
 * How many of a vertical stack's children, [heightsDp] tall, fit whole in [availableDp]: one at
 * least.
 */
internal fun fittingChildren(heightsDp: List<Int>, availableDp: Int): Int {
  var used = 0
  var count = 0
  for (h in heightsDp) {
    val next = used + (if (count > 0) VERTICAL_STACK_GAP_DP else 0) + h
    if (next > availableDp) break
    used = next
    count++
  }
  return count.coerceAtLeast(1)
}

/** The gap [RemoteHaVerticalStack] leaves between children. */
private const val VERTICAL_STACK_GAP_DP = 8

class HorizontalStackCardConverter : DesignedCardConverter() {
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

  /**
   * The container and its children, as every surface draws it (`horizontal-stack.uid`,
   * [ContainerDesignParts]).
   */
  @Composable
  internal fun Draw(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    // HA splits a horizontal stack's width evenly; each child is captured for its own column. A
    // launcher widget keeps the children whose column is still a 48 dp target, rather than
    // squeezing every child into a sliver.
    val all = childCards(card)
    val children =
      LocalWidgetCaptureSizeDp.current?.let { cell ->
        val fits =
          (cell.widthDp + HORIZONTAL_STACK_GAP_DP) /
            (HaGridLayout.MIN_FIXED_CELL_DP + HORIZONTAL_STACK_GAP_DP)
        all.take(fits.coerceAtLeast(1))
      } ?: all
    ProvideChildCaptureShare(columns = children.size, gapDp = HORIZONTAL_STACK_GAP_DP) {
      CompositionLocalProvider(
        LocalGridSquareCells provides false,
        LocalInCardContainer provides true,
      ) {
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
class GridCardConverter : DesignedCardConverter() {
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

  /**
   * The container and its children, as every surface draws it (`grid.uid`, [ContainerDesignParts]).
   */
  @Composable
  internal fun Draw(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    // Cells for what the children draw now: a hidden conditional takes no cell, a shown one is
    // sized as the card it shows. Each keeps its authored index for its card-level template
    // bindings.
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
    // A grid holding a picture or a square cell keeps HA's columns (gridKeepsColumnWidth): its
    // short last row isn't stretched, and fewer cards than columns still lay out a column wide, so
    // a lone picture is a column's square rather than one as wide as the grid.
    val keepColumns =
      children.indices.any { k ->
        converters[k]?.gridKeepsColumnWidth(children[k], snapshot) == true
      }
    val planCount = if (keepColumns) maxOf(children.size, columns) else children.size
    // A launcher capture is re-recorded for every size it is shown at, so it lays its rows out for
    // exactly that width. Everything else is captured once and picks a tier at playback, its narrow
    // tier sized for the narrowest width its surface plays at.
    val captureWidthDp = LocalWidgetCaptureSizeDp.current?.widthDp
    val narrowReferenceDp =
      when (LocalCardSizeMode.current) {
        CardSizeMode.Wrap -> HaGridLayout.NARROW_REFERENCE_DP
        CardSizeMode.Fixed -> HaGridLayout.FIXED_NARROW_REFERENCE_DP
      }
    val plan = HaGridLayout.plan(planCount, columns, minCellDp, narrowReferenceDp)

    CompositionLocalProvider(
      LocalGridSquareCells provides square,
      LocalInCardContainer provides true,
    ) {
      val threshold = plan.thresholdDp
      val capture = LocalWidgetCaptureSizeDp.current
      if (captureWidthDp != null && capture != null) {
        // The widget's height decides too: a short widget puts more, smaller cells in a row and
        // shows only the cards that fit, rather than stacking rows past its bottom edge.
        val fit =
          HaGridLayout.fit(
            widthDp = captureWidthDp,
            heightDp = capture.heightDp,
            cellCount = children.size,
            perRow = HaGridLayout.perRowAt(captureWidthDp.toFloat(), planCount, columns, minCellDp),
            square = squareCells.any { it },
          )
        val perRow = fit.perRow
        val rows = (fit.shown + perRow - 1) / perRow
        // Each cell is captured for its own share of the widget, so a nested grid or size gate
        // lays out for its cell rather than the whole widget.
        ProvideChildCaptureShare(columns = perRow, rows = rows, gapDp = HaGridLayout.GAP_DP) {
          GridCells(
            children.take(fit.shown),
            bindingIndices.take(fit.shown),
            squareCells.take(fit.shown),
            keepColumns,
            snapshot,
            perRow,
            modifier,
          )
        }
      } else if (
        LocalCardSizeMode.current == CardSizeMode.Fixed &&
          LocalFixedCanvasAxis.current == BreakpointAxis.Height
      ) {
        // A Wear slot is wide and short (160 to 200 × 60 to 136 dp): one cell per row, each a
        // square as wide as the slot, showed only the first cell (#624). Up to three cells share
        // one row, as a horizontal stack's do; more take two rows, the most a Large slot holds.
        // None is square, since a square cell is as tall as the slot is wide. Past
        // [WEAR_GRID_MAX_CELLS] the cells would be narrower than a 48 dp chip, so the rest drop.
        val wearCells = children.take(WEAR_GRID_MAX_CELLS)
        GridCells(
          wearCells,
          bindingIndices.take(wearCells.size),
          List(wearCells.size) { false },
          keepColumns = false,
          snapshot,
          wearGridPerRow(wearCells.size),
          modifier.fillMaxSize(),
          fillHeight = true,
        )
      } else if (threshold == null) {
        GridCells(
          children,
          bindingIndices,
          squareCells,
          keepColumns,
          snapshot,
          plan.widePerRow,
          modifier,
        )
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
            keepColumns,
            snapshot,
            perRow,
            RemoteModifier.fillMaxWidth(),
          )
        }
      }
    }
  }

  internal companion object {
    const val COLUMNS = "columns"
    const val SQUARE = "square"

    /** A child whose converter can't be found: the unsupported placeholder's compact default. */
    const val DEFAULT_MIN_CELL_DP = 120
  }
}

/**
 * [children] in rows of [perRow]; a [squareCells] child fills a square cell. A short last row
 * stretches across the grid unless [keepColumns]. [bindingIndices] is each child's authored index,
 * for its card-level template bindings.
 */
@Composable
private fun GridCells(
  children: List<CardConfig>,
  bindingIndices: List<Int>,
  squareCells: List<Boolean>,
  keepColumns: Boolean,
  snapshot: HaSnapshot,
  perRow: Int,
  modifier: RemoteModifier,
  fillHeight: Boolean = false,
) {
  RemoteHaGrid(
    cellCount = children.size,
    perRow = perRow,
    modifier = modifier,
    stretchLastRow = !keepColumns,
    fillHeight = fillHeight,
  ) { k ->
    ProvideChildCardBindings(bindingIndices[k]) {
      if (squareCells[k]) {
        RemoteAspectRatioBox(1f, RemoteModifier.fillMaxWidth()) {
          RenderChild(children[k], snapshot, RemoteModifier.fillMaxSize())
        }
      } else {
        RenderChild(
          children[k],
          snapshot,
          if (fillHeight) RemoteModifier.fillMaxSize() else RemoteModifier.fillMaxWidth(),
        )
      }
    }
  }
}

/**
 * Cells per row of a grid of [cellCount] cards in a Wear slot: one row up to three, else two rows,
 * never more than three to a row (a 160 dp slot fits three 48 dp chips).
 */
internal fun wearGridPerRow(cellCount: Int): Int =
  if (cellCount <= 3) cellCount.coerceAtLeast(1) else ((cellCount + 1) / 2).coerceAtMost(3)

/** The most cells a Wear slot's grid shows: two rows of three, all a Large slot holds. */
internal const val WEAR_GRID_MAX_CELLS = 6

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
 * [indices] picks which children draw (all by default); each keeps its authored index's bindings.
 */
@Composable
private fun RenderChildren(
  children: List<CardConfig>,
  snapshot: HaSnapshot,
  indices: List<Int> = children.indices.toList(),
) {
  CompositionLocalProvider(
    LocalGridSquareCells provides false,
    LocalInCardContainer provides true,
  ) {
    indices.forEach { k -> ProvideChildCardBindings(k) { RenderChild(children[k], snapshot) } }
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
