@file:Suppress("RestrictedApi")
@file:OptIn(ExperimentalFlexBoxApi::class)

package ee.schimke.ha.rc.components

import androidx.compose.foundation.layout.ExperimentalFlexBoxApi
import androidx.compose.foundation.layout.FlexAlignContent
import androidx.compose.foundation.layout.FlexAlignItems
import androidx.compose.foundation.layout.FlexAlignSelf
import androidx.compose.foundation.layout.FlexBasis
import androidx.compose.foundation.layout.FlexBoxConfig
import androidx.compose.foundation.layout.FlexBoxConfigScope
import androidx.compose.foundation.layout.FlexConfig
import androidx.compose.foundation.layout.FlexConfigScope
import androidx.compose.foundation.layout.FlexDirection
import androidx.compose.foundation.layout.FlexJustifyContent
import androidx.compose.foundation.layout.FlexWrap
import androidx.compose.foundation.layout.LayoutScopeMarker
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteColumnScope
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteFlowRow
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteRowScope
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.alignByBaseline
import androidx.compose.remote.creation.compose.modifier.fillMaxHeight
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.fillParentMaxHeight
import androidx.compose.remote.creation.compose.modifier.fillParentMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.heightIn
import androidx.compose.remote.creation.compose.modifier.width
import androidx.compose.remote.creation.compose.modifier.widthIn
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Measured
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * The Remote Compose counterpart of `androidx.compose.foundation.layout.FlexBox`.
 *
 * The API is upstream's, member for member, over [RemoteModifier]: `RemoteFlexBox(modifier, config,
 * content)` mirrors `FlexBox(modifier, config, content)`, [RemoteFlexBoxConfig] /
 * [RemoteFlexBoxConfigScope] mirror `FlexBoxConfig` / `FlexBoxConfigScope`, and
 * [RemoteFlexBoxScope.flex] with [RemoteFlexConfig] / [RemoteFlexConfigScope] mirrors
 * `FlexBoxScope.flex` with `FlexConfig` / `FlexConfigScope`. The values are upstream's own —
 * `FlexDirection`, `FlexWrap`, `FlexJustifyContent`, `FlexAlignItems`, `FlexAlignContent`,
 * `FlexAlignSelf`, `FlexBasis` — so a config lambda reads the same on both:
 * ```
 * RemoteFlexBox(
 *   config = {
 *     wrap(FlexWrap.Wrap)
 *     justifyContent(FlexJustifyContent.SpaceBetween)
 *     columnGap(8.dp)
 *   },
 * ) {
 *   RemoteText("A", RemoteModifier.flex { grow(1f); basis(64.dp) })
 *   RemoteText("B")
 * }
 * ```
 *
 * The config types are mirrors rather than upstream's because upstream seals its scopes, so only
 * its own measure policy can run a `FlexBoxConfig`. The bridge goes the other way: a remote config
 * converts with [asFlexBoxConfig] / [asFlexConfig], so one config can drive a `FlexBox` and a
 * `RemoteFlexBox` side by side.
 *
 * A `.rc` document has no flexbox operation, so the config is resolved **at capture time** and
 * lowered onto the layout managers the player does have: a `Row` / `Column` for a single line and
 * the experimental `FlowLayout` (op 240, the one `RemoteFlowRow` writes) for `FlexWrap.Wrap`. The
 * config scope therefore sees unbounded [RemoteFlexBoxConfigScope.constraints]: the document is
 * resized at playback, long after the config ran.
 *
 * What carries over, checked line by line against `FlexBox` in `FlexBoxPreviews.kt`:
 * - `direction(Row / Column)`: a `RemoteRow` / `RemoteColumn`. Exact.
 * - `direction(RowReverse)`: a `RemoteRow` laid out right-to-left, with the children composed back
 *   under the caller's direction. Exact.
 * - `justifyContent`: all six values map to the main-axis arrangement. Exact.
 * - `alignItems(Start / Center / End)`: the cross-axis alignment. Exact.
 * - `alignItems(Baseline)` (rows): `alignByBaseline()` on each `flex` child. Exact.
 * - `alignItems(Stretch)` / `alignSelf(Stretch)`: `fillMaxHeight()` (rows) or `fillMaxWidth()`
 *   (columns) on each `flex` child, which beats an explicit cross size written after `flex`, as
 *   upstream's Stretch does. Exact when the box has a definite cross size. A wrap-content row
 *   stretches its items to the height it is offered rather than to its tallest item: the player's
 *   intrinsic sizing, which would measure the line, drops a row of content-sized children entirely.
 * - `columnGap` / `rowGap` / `gap` along the main axis: `spacedBy`. Exact, except that the three
 *   `Space*` justifications ignore it; they distribute all the free space already.
 * - `grow`: `weight`. Exact when the growing items' bases are all zero, or equal with equal `grow`;
 *   from an auto basis the content width is ignored. Combined with a gap, the line overflows by the
 *   gaps: the player's Row hands weighted children the whole remainder before adding the spacing.
 * - `basis(dp)`: `width` / `height`, or with `grow` a `widthIn(min)` / `heightIn(min)` floor.
 * - `basis(fraction)` / `FlexBasis.Percent`: `fillParentMaxWidth` / `fillParentMaxHeight`, a
 *   fraction of the box. Exact while the line fits.
 * - `shrink`: ignored. Remote layouts never shrink an item, so an over-full line overflows.
 * - `wrap(Wrap)` (rows): `RemoteFlowRow`, the experimental `FlowLayout` op, so the capture profile
 *   must admit it. Line breaking, `justifyContent`, `columnGap` and line alignment are exact.
 *   `rowGap` is dropped (`FlowLayout` has no line spacing); lines past the box's available height
 *   are hidden rather than overflowing; growing items do not reliably fill their line; and one
 *   vertical positioning serves both `alignItems` and `alignContent`, so `alignContent(Start /
 *   Center / End)` applies only while `alignItems` is `Start`.
 *
 * What cannot, and throws [UnsupportedOperationException] at capture rather than rendering
 * something else: `order` other than 0, `FlexDirection.ColumnReverse`, `FlexWrap.WrapReverse`,
 * wrapping a column or a reversed row, `alignContent(Stretch / SpaceBetween / SpaceAround)` while
 * wrapping, `alignSelf(Start / Center / End)` that differs from the box's `alignItems`, and
 * alignment by an arbitrary [AlignmentLine] or a `(Measured) -> Int` block (`FirstBaseline` maps to
 * `Baseline`). Each needs the layout to reorder, wrap or individually place a child, which the
 * player's Row / Column / Flow managers do not do and a modifier cannot express.
 *
 * Item behaviour arrives through [RemoteFlexBoxScope.flex], so a child that never calls it keeps
 * the box's plain alignment and gets no `Stretch` / `Baseline`; upstream stretches every child. The
 * first `width` / `height` in a remote chain wins, so a size written before `flex` beats `grow`,
 * `basis` and `Stretch`.
 */
@ExperimentalFlexBoxApi
@RemoteComposable
@Composable
fun RemoteFlexBox(
  modifier: RemoteModifier = RemoteModifier,
  config: RemoteFlexBoxConfig = RemoteFlexBoxConfig,
  content: @Composable @RemoteComposable RemoteFlexBoxScope.() -> Unit,
) {
  val density = LocalDensity.current
  val box =
    remember(config, density) {
      ResolvedRemoteFlexBox(density).also { with(config) { it.configure() } }
    }
  val scope = remember(box) { RemoteFlexBoxScopeInstance(box) }
  when {
    box.isRow && box.wraps ->
      RemoteFlowRow(
        modifier = modifier,
        horizontalArrangement = box.horizontalArrangement(),
        verticalArrangement = box.flowLineArrangement(),
      ) {
        scope.content()
      }
    box.isRow -> {
      // RowReverse: a right-to-left row reverses its children and mirrors Start / End, which is
      // exactly a reversed main axis. Only the row sees the flipped direction; the children are
      // composed back under the caller's, so their text and nested rows are not mirrored.
      val outer = LocalLayoutDirection.current
      val rowDirection =
        when {
          box.direction != FlexDirection.RowReverse -> outer
          outer == LayoutDirection.Ltr -> LayoutDirection.Rtl
          else -> LayoutDirection.Ltr
        }
      CompositionLocalProvider(LocalLayoutDirection provides rowDirection) {
        RemoteRow(
          modifier = modifier,
          horizontalArrangement = box.horizontalArrangement(),
          verticalAlignment = box.verticalAlignment(),
        ) {
          CompositionLocalProvider(LocalLayoutDirection provides outer) { scope.content() }
        }
      }
    }
    else ->
      RemoteColumn(
        modifier = modifier,
        verticalArrangement = box.verticalArrangement(),
        horizontalAlignment = box.horizontalAlignment(),
      ) {
        scope.content()
      }
  }
}

/** The receiver of [RemoteFlexBox]'s content: upstream `FlexBoxScope`, over [RemoteModifier]. */
@ExperimentalFlexBoxApi
@LayoutScopeMarker
sealed interface RemoteFlexBoxScope {
  /** Applies [flexConfig] to this child: upstream `FlexBoxScope.flex(FlexConfig)`. */
  fun RemoteModifier.flex(flexConfig: RemoteFlexConfig): RemoteModifier

  /** Configures this child inline: upstream `FlexBoxScope.flex { grow(1f) }`. */
  fun RemoteModifier.flex(flexConfig: RemoteFlexConfigScope.() -> Unit): RemoteModifier =
    flex(RemoteFlexConfig { flexConfig() })
}

/**
 * A [RemoteFlexBox] container configuration: upstream `FlexBoxConfig`. The companion is the empty
 * configuration — every upstream default: `Row`, `NoWrap`, `Start` throughout, no gaps.
 */
@ExperimentalFlexBoxApi
fun interface RemoteFlexBoxConfig {
  fun RemoteFlexBoxConfigScope.configure()

  /** This configuration, then [other]'s; a later setting wins. */
  infix fun then(other: RemoteFlexBoxConfig): RemoteFlexBoxConfig = RemoteFlexBoxConfig {
    with(this@RemoteFlexBoxConfig) { configure() }
    with(other) { configure() }
  }

  companion object : RemoteFlexBoxConfig {
    override fun RemoteFlexBoxConfigScope.configure() = Unit
  }
}

/** Combines [configs] in order: upstream `FlexBoxConfig(vararg)`. */
@ExperimentalFlexBoxApi
fun RemoteFlexBoxConfig(vararg configs: RemoteFlexBoxConfig): RemoteFlexBoxConfig =
  configs.fold(RemoteFlexBoxConfig as RemoteFlexBoxConfig) { acc, next -> acc then next }

/** The receiver of a [RemoteFlexBoxConfig]: upstream `FlexBoxConfigScope`, member for member. */
@ExperimentalFlexBoxApi
sealed interface RemoteFlexBoxConfigScope : Density {
  /** The box's incoming constraints. Unbounded for a [RemoteFlexBox]: it is sized at playback. */
  val constraints: Constraints

  fun direction(value: FlexDirection)

  fun wrap(value: FlexWrap)

  fun justifyContent(value: FlexJustifyContent)

  fun alignItems(value: FlexAlignItems)

  fun alignItems(alignmentLine: AlignmentLine)

  fun alignItems(alignmentLineBlock: (Measured) -> Int)

  fun alignContent(value: FlexAlignContent)

  fun rowGap(value: Dp)

  fun columnGap(value: Dp)

  fun gap(value: Dp)

  fun gap(row: Dp, column: Dp)
}

/** A [RemoteFlexBox] child configuration: upstream `FlexConfig`. The companion is empty. */
@ExperimentalFlexBoxApi
fun interface RemoteFlexConfig {
  fun RemoteFlexConfigScope.configure()

  /** This configuration, then [other]'s; a later setting wins. */
  infix fun then(other: RemoteFlexConfig): RemoteFlexConfig = RemoteFlexConfig {
    with(this@RemoteFlexConfig) { configure() }
    with(other) { configure() }
  }

  companion object : RemoteFlexConfig {
    override fun RemoteFlexConfigScope.configure() = Unit
  }
}

/** Combines [configs] in order: upstream `FlexConfig(vararg)`. */
@ExperimentalFlexBoxApi
fun RemoteFlexConfig(vararg configs: RemoteFlexConfig): RemoteFlexConfig =
  configs.fold(RemoteFlexConfig as RemoteFlexConfig) { acc, next -> acc then next }

/** The receiver of a [RemoteFlexConfig]: upstream `FlexConfigScope`, member for member. */
@ExperimentalFlexBoxApi
sealed interface RemoteFlexConfigScope : Density {
  /** Upstream reads these from the measured box; a capture has none, so the main axis is open. */
  val flexBoxMainAxisMax: Int
  val flexBoxMainAxisMin: Int
  val flexBoxCrossAxisMax: Int
  val flexBoxCrossAxisMin: Int

  fun alignSelf(value: FlexAlignSelf)

  fun alignSelf(alignmentLine: AlignmentLine)

  fun alignSelf(alignmentLineBlock: (Measured) -> Int)

  fun order(value: Int)

  fun grow(value: Float)

  fun shrink(value: Float)

  fun basis(value: FlexBasis)

  /** A fixed basis: upstream `basis(Dp)`. */
  fun basis(value: Dp)

  /** A fraction of the box's main axis, `0f..1f`: upstream `basis(Float)`. */
  fun basis(value: Float)
}

/** This configuration as an upstream `FlexBoxConfig`, to drive a `FlexBox` with the same values. */
@ExperimentalFlexBoxApi
fun RemoteFlexBoxConfig.asFlexBoxConfig(): FlexBoxConfig {
  val remote = this
  return FlexBoxConfig {
    val upstream = UpstreamFlexBoxConfigScope(this)
    with(remote) { upstream.configure() }
  }
}

/** This configuration as an upstream `FlexConfig`, for a child of a `FlexBox`. */
@ExperimentalFlexBoxApi
fun RemoteFlexConfig.asFlexConfig(): FlexConfig {
  val remote = this
  return FlexConfig {
    val upstream = UpstreamFlexConfigScope(this)
    with(remote) { upstream.configure() }
  }
}

private class UpstreamFlexBoxConfigScope(private val scope: FlexBoxConfigScope) :
  RemoteFlexBoxConfigScope, Density by scope {
  override val constraints: Constraints
    get() = scope.constraints

  override fun direction(value: FlexDirection) = scope.direction(value)

  override fun wrap(value: FlexWrap) = scope.wrap(value)

  override fun justifyContent(value: FlexJustifyContent) = scope.justifyContent(value)

  override fun alignItems(value: FlexAlignItems) = scope.alignItems(value)

  override fun alignItems(alignmentLine: AlignmentLine) = scope.alignItems(alignmentLine)

  override fun alignItems(alignmentLineBlock: (Measured) -> Int) =
    scope.alignItems(alignmentLineBlock)

  override fun alignContent(value: FlexAlignContent) = scope.alignContent(value)

  override fun rowGap(value: Dp) = scope.rowGap(value)

  override fun columnGap(value: Dp) = scope.columnGap(value)

  override fun gap(value: Dp) = scope.gap(value)

  override fun gap(row: Dp, column: Dp) = scope.gap(row, column)
}

private class UpstreamFlexConfigScope(private val scope: FlexConfigScope) :
  RemoteFlexConfigScope, Density by scope {
  override val flexBoxMainAxisMax: Int
    get() = scope.flexBoxMainAxisMax

  override val flexBoxMainAxisMin: Int
    get() = scope.flexBoxMainAxisMin

  override val flexBoxCrossAxisMax: Int
    get() = scope.flexBoxCrossAxisMax

  override val flexBoxCrossAxisMin: Int
    get() = scope.flexBoxCrossAxisMin

  override fun alignSelf(value: FlexAlignSelf) = scope.alignSelf(value)

  override fun alignSelf(alignmentLine: AlignmentLine) = scope.alignSelf(alignmentLine)

  override fun alignSelf(alignmentLineBlock: (Measured) -> Int) =
    scope.alignSelf(alignmentLineBlock)

  override fun order(value: Int) = scope.order(value)

  override fun grow(value: Float) = scope.grow(value)

  override fun shrink(value: Float) = scope.shrink(value)

  override fun basis(value: FlexBasis) = scope.basis(value)

  override fun basis(value: Dp) = scope.basis(value)

  override fun basis(value: Float) = scope.basis(value)
}

private class RemoteFlexBoxScopeInstance(private val box: ResolvedRemoteFlexBox) :
  RemoteFlexBoxScope {
  override fun RemoteModifier.flex(flexConfig: RemoteFlexConfig): RemoteModifier {
    val item =
      ResolvedRemoteFlexItem(box.captureDensity).also { with(flexConfig) { it.configure() } }
    if (item.order != 0) unsupported("order(${item.order})")
    return mainAxis(item).crossAxis(item)
  }

  private fun RemoteModifier.mainAxis(item: ResolvedRemoteFlexItem): RemoteModifier {
    val basis = item.basis
    if (item.grow > 0f) {
      // Remote weights share the space the unweighted siblings leave, from a zero basis; a dp
      // basis survives as the floor `FlowLayout` also breaks lines on.
      val weighted =
        if (box.isRow) with(RowScope) { weight(item.grow.rf) }
        else with(ColumnScope) { weight(item.grow.rf) }
      return when {
        basis !is Basis.Fixed -> weighted
        box.isRow -> weighted.widthIn(min = basis.size.value.rdp)
        else -> weighted.heightIn(min = basis.size.value.rdp)
      }
    }
    return when (basis) {
      Basis.Auto -> this
      is Basis.Fixed -> if (box.isRow) width(basis.size.value.rdp) else height(basis.size.value.rdp)
      is Basis.Fraction ->
        if (box.isRow) fillParentMaxWidth(basis.fraction) else fillParentMaxHeight(basis.fraction)
    }
  }

  private fun RemoteModifier.crossAxis(item: ResolvedRemoteFlexItem): RemoteModifier =
    when (item.alignSelf) {
      FlexAlignSelf.Auto -> crossAxis(box.alignItems)
      FlexAlignSelf.Stretch -> crossAxis(FlexAlignItems.Stretch)
      FlexAlignSelf.Baseline -> crossAxis(FlexAlignItems.Baseline)
      else -> {
        val own =
          when (item.alignSelf) {
            FlexAlignSelf.Start -> FlexAlignItems.Start
            FlexAlignSelf.End -> FlexAlignItems.End
            else -> FlexAlignItems.Center
          }
        // The line places every child by the box's alignment; a child cannot be placed apart.
        if (own != box.alignItems)
          unsupported("alignSelf(${item.alignSelf}) under alignItems(${box.alignItems})")
        this
      }
    }

  private fun RemoteModifier.crossAxis(align: FlexAlignItems): RemoteModifier =
    when (align) {
      FlexAlignItems.Stretch -> if (box.isRow) fillMaxHeight() else fillMaxWidth()
      FlexAlignItems.Baseline -> if (box.isRow) alignByBaseline() else this
      else -> this
    }

  private companion object {
    // Only their weight() extensions are used; both scopes are stateless.
    val RowScope = RemoteRowScope()
    val ColumnScope = RemoteColumnScope()
  }
}

/** Records a [RemoteFlexBoxConfig] as upstream's measure policy would, from upstream's defaults. */
private class ResolvedRemoteFlexBox(val captureDensity: Density) :
  RemoteFlexBoxConfigScope, Density by captureDensity {
  override val constraints: Constraints = Constraints()
  var direction = FlexDirection.Row
  var wrap = FlexWrap.NoWrap
  var justifyContent = FlexJustifyContent.Start
  var alignItems = FlexAlignItems.Start
  var alignContent = FlexAlignContent.Start
  var rowGap: Dp = 0.dp
  var columnGap: Dp = 0.dp

  override fun direction(value: FlexDirection) {
    direction = value
  }

  override fun wrap(value: FlexWrap) {
    wrap = value
  }

  override fun justifyContent(value: FlexJustifyContent) {
    justifyContent = value
  }

  override fun alignItems(value: FlexAlignItems) {
    alignItems = value
  }

  override fun alignItems(alignmentLine: AlignmentLine) {
    if (alignmentLine != FirstBaseline) unsupported("alignItems($alignmentLine)")
    alignItems = FlexAlignItems.Baseline
  }

  override fun alignItems(alignmentLineBlock: (Measured) -> Int) {
    unsupported("alignItems { measured -> … }")
  }

  override fun alignContent(value: FlexAlignContent) {
    alignContent = value
  }

  override fun rowGap(value: Dp) {
    rowGap = value
  }

  override fun columnGap(value: Dp) {
    columnGap = value
  }

  override fun gap(value: Dp) {
    rowGap = value
    columnGap = value
  }

  override fun gap(row: Dp, column: Dp) {
    rowGap = row
    columnGap = column
  }

  val isRow: Boolean
    get() =
      when (direction) {
        FlexDirection.Row,
        FlexDirection.RowReverse -> true
        FlexDirection.Column -> false
        else -> unsupported("direction($direction)")
      }

  val wraps: Boolean
    get() =
      when (wrap) {
        FlexWrap.NoWrap -> false
        FlexWrap.Wrap -> {
          if (!isRow) unsupported("wrap(Wrap) with direction($direction)")
          if (direction == FlexDirection.RowReverse)
            unsupported("wrap(Wrap) with direction(RowReverse)")
          true
        }
        else -> unsupported("wrap($wrap)")
      }

  /** The row's main axis. Space-distributing justifications have no use for a gap. */
  fun horizontalArrangement(): RemoteArrangement.Horizontal {
    val gap = columnGap.value
    return when (justifyContent) {
      FlexJustifyContent.SpaceBetween -> RemoteArrangement.SpaceBetween
      FlexJustifyContent.SpaceAround -> RemoteArrangement.SpaceAround
      FlexJustifyContent.SpaceEvenly -> RemoteArrangement.SpaceEvenly
      else -> {
        val alignment =
          when (justifyContent) {
            FlexJustifyContent.End -> RemoteAlignment.End
            FlexJustifyContent.Center -> RemoteAlignment.CenterHorizontally
            else -> RemoteAlignment.Start
          }
        if (gap > 0f) RemoteArrangement.spacedBy(gap.rdp, alignment)
        else
          when (justifyContent) {
            FlexJustifyContent.End -> RemoteArrangement.End
            FlexJustifyContent.Center -> RemoteArrangement.Center
            else -> RemoteArrangement.Start
          }
      }
    }
  }

  /** The column's main axis. */
  fun verticalArrangement(): RemoteArrangement.Vertical {
    val gap = rowGap.value
    return when (justifyContent) {
      FlexJustifyContent.SpaceBetween -> RemoteArrangement.SpaceBetween
      FlexJustifyContent.SpaceAround -> RemoteArrangement.SpaceAround
      FlexJustifyContent.SpaceEvenly -> RemoteArrangement.SpaceEvenly
      else -> {
        val alignment =
          when (justifyContent) {
            FlexJustifyContent.End -> RemoteAlignment.Bottom
            FlexJustifyContent.Center -> RemoteAlignment.CenterVertically
            else -> RemoteAlignment.Top
          }
        if (gap > 0f) RemoteArrangement.spacedBy(gap.rdp, alignment)
        else
          when (justifyContent) {
            FlexJustifyContent.End -> RemoteArrangement.Bottom
            FlexJustifyContent.Center -> RemoteArrangement.Center
            else -> RemoteArrangement.Top
          }
      }
    }
  }

  /** A single row's cross axis. `Stretch` and `Baseline` start at the top, as upstream's do. */
  fun verticalAlignment(): RemoteAlignment.Vertical =
    when (alignItems) {
      FlexAlignItems.Center -> RemoteAlignment.CenterVertically
      FlexAlignItems.End -> RemoteAlignment.Bottom
      else -> RemoteAlignment.Top
    }

  /** A single column's cross axis. */
  fun horizontalAlignment(): RemoteAlignment.Horizontal =
    when (alignItems) {
      FlexAlignItems.Center -> RemoteAlignment.CenterHorizontally
      FlexAlignItems.End -> RemoteAlignment.End
      else -> RemoteAlignment.Start
    }

  /**
   * `FlowLayout` has one vertical positioning that both aligns the children inside each line and
   * places the block of lines, so `alignItems` and `alignContent` share it: `alignItems` wins
   * unless it is `Start`.
   */
  fun flowLineArrangement(): RemoteArrangement.Vertical {
    val cross =
      when (alignItems) {
        FlexAlignItems.Center -> FlexAlignContent.Center
        FlexAlignItems.End -> FlexAlignContent.End
        else ->
          when (alignContent) {
            FlexAlignContent.Start,
            FlexAlignContent.Center,
            FlexAlignContent.End -> alignContent
            else -> unsupported("alignContent($alignContent) while wrapping")
          }
      }
    return when (cross) {
      FlexAlignContent.Center -> RemoteArrangement.Center
      FlexAlignContent.End -> RemoteArrangement.Bottom
      else -> RemoteArrangement.Top
    }
  }
}

/** A child's main-axis basis, decoded from upstream's packed [FlexBasis]. */
internal sealed interface Basis {
  data object Auto : Basis

  data class Fixed(val size: Dp) : Basis

  data class Fraction(val fraction: Float) : Basis
}

/** Records one child's [RemoteFlexConfig], from upstream's item defaults. */
private class ResolvedRemoteFlexItem(density: Density) : RemoteFlexConfigScope, Density by density {
  override val flexBoxMainAxisMax: Int = Constraints.Infinity
  override val flexBoxMainAxisMin: Int = 0
  override val flexBoxCrossAxisMax: Int = Constraints.Infinity
  override val flexBoxCrossAxisMin: Int = 0
  var alignSelf = FlexAlignSelf.Auto
  var order = 0
  var grow = 0f
  var shrink = 1f
  var basis: Basis = Basis.Auto

  override fun alignSelf(value: FlexAlignSelf) {
    alignSelf = value
  }

  override fun alignSelf(alignmentLine: AlignmentLine) {
    if (alignmentLine != FirstBaseline) unsupported("alignSelf($alignmentLine)")
    alignSelf = FlexAlignSelf.Baseline
  }

  override fun alignSelf(alignmentLineBlock: (Measured) -> Int) {
    unsupported("alignSelf { measured -> … }")
  }

  override fun order(value: Int) {
    order = value
  }

  override fun grow(value: Float) {
    grow = value
  }

  override fun shrink(value: Float) {
    shrink = value
  }

  override fun basis(value: FlexBasis) {
    basis = value.decode()
  }

  override fun basis(value: Dp) {
    basis = Basis.Fixed(value)
  }

  override fun basis(value: Float) {
    basis = Basis.Fraction(value)
  }
}

/**
 * [FlexBasis] keeps its kind and value private, packed into one `Long` as `kind << 32 | floatBits`
 * (kind 1 is dp, 2 is a fraction). Its public `hashCode` is `Long.hashCode` of that word, which is
 * `floatBits xor kind`, and its `toString` names the kind — enough to recover both exactly. The
 * round trip through upstream's own constructors guards against a change in the packing.
 */
internal fun FlexBasis.decode(): Basis {
  if (this == FlexBasis.Auto) return Basis.Auto
  val text = toString()
  val kind =
    when {
      text.startsWith("FlexBasis.Dp") -> 1
      text.startsWith("FlexBasis.Percent") -> 2
      else -> unsupported("basis($text)")
    }
  val value = Float.fromBits(hashCode() xor kind)
  return if (kind == 1) {
    Basis.Fixed(value.dp).also { check(FlexBasis.Dp(value.dp) == this) { "undecodable $text" } }
  } else {
    Basis.Fraction(value).also { check(FlexBasis.Percent(value) == this) { "undecodable $text" } }
  }
}

private fun unsupported(what: String): Nothing =
  throw UnsupportedOperationException(
    "RemoteFlexBox: $what has no Remote Compose equivalent (see RemoteFlexBox KDoc)"
  )
