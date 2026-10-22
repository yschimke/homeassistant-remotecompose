@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteFlowRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.width
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.toRemoteDp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.SectionCardFlow
import ee.schimke.ha.model.SectionCardGrid
import ee.schimke.ha.model.SectionFlowItem
import ee.schimke.ha.model.SectionFlowSplit
import ee.schimke.ha.rc.components.LocalGridCellWidthPx
import ee.schimke.ha.rc.components.rememberExpressionName
import java.text.DecimalFormat

/**
 * One section's cards in a single document, flowed by the player rather than by the host: the
 * remote counterpart of [LovelaceSectionCardFlow], with the same rows at every width.
 *
 * [SectionCardFlow] is flexbox `wrap` with each card at `basis(minWidth)` and `grow(minWidth)`, but
 * the player's `FlowLayout` cannot run it: it doesn't grow weighted items within a line, and a card
 * that fills its width takes a whole line. So the player is only asked to break lines, and each
 * card's width is a remote expression of the section's played width:
 * - [SectionCardFlow.splits] lists every way the cards split into rows below [MaxSectionWidthDp];
 *   which one applies depends only on the width.
 * - For each card, the width is its row's share by minimum, `(width − gaps) × min ÷ row total`, as
 *   [SectionCardFlow.rows] gives it, chosen by `select` from the split the played width falls in.
 *   Shares sum to the row's width less [SeamPx] per card, so rounding never pushes a card down.
 * - The row gap is a top padding on every card past the first row, chosen the same way, since
 *   `FlowLayout` has no line spacing.
 *
 * The document re-flows at whatever width it is played at, with no re-capture. Each card also gets
 * its width as [LocalGridCellWidthPx], so a picture sizes its height from it. [items] are in dp
 * (see [sectionFlowItems]), one per card.
 */
@Composable
@RemoteComposable
fun RemoteSectionCardFlow(
  cards: List<CardConfig>,
  items: List<SectionFlowItem>,
  snapshot: HaSnapshot,
  modifier: RemoteModifier = RemoteModifier,
) {
  require(cards.size == items.size) {
    "RemoteSectionCardFlow expects one item per card (${cards.size}), got ${items.size}"
  }
  val gapDp = SectionCardGrid.COLUMN_GAP_DP
  val splits = remember(items) { SectionCardFlow.splits(items, gapDp, MaxSectionWidthDp) }
  val name = rememberExpressionName(SectionWidthExpressionPrefix)
  val sectionWidthPx =
    RemoteFloat.createNamedRemoteFloatExpression(name = name, domain = RemoteState.Domain.User) {
      componentWidth()
    }
  val gapPx = gapDp.rdp.toPx()
  val rowGapPx = SectionCardGrid.ROW_GAP_DP.rdp.toPx()

  /**
   * The value for the split the played width falls in. [key] says what the value depends on, so
   * neighbouring splits that give a card the same value share one branch.
   */
  fun <K> bySplit(
    key: (SectionFlowSplit) -> K,
    value: (SectionFlowSplit) -> RemoteFloat,
  ): RemoteFloat {
    // Each run of splits with one key, by the width it starts at.
    val runs = mutableListOf<Pair<SectionFlowSplit, K>>()
    for (split in splits) {
      val k = key(split)
      if (runs.lastOrNull()?.second != k) runs += split to k
    }
    // Widest first: each narrower run takes over below the start of the run after it.
    var chosen = value(runs.last().first)
    for (r in runs.lastIndex - 1 downTo 0) {
      chosen =
        sectionWidthPx
          .isGreaterThanOrEqualTo(runs[r + 1].first.fromWidth.rdp.toPx())
          .select(chosen, value(runs[r].first))
    }
    return chosen
  }

  RemoteBox(modifier = modifier.fillMaxWidth()) {
    // Writes the width expression while this box is the component being written, so it reads the
    // section's width (the forcing text `RemoteHaGrid` uses; it draws nothing).
    RemoteText(text = sectionWidthPx.toRemoteString(WidthFormat), color = Color.Transparent.rc)
    RemoteFlowRow(
      modifier = RemoteModifier.fillMaxWidth(),
      horizontalArrangement = RemoteArrangement.spacedBy(gapDp.rdp),
    ) {
      cards.forEachIndexed { i, card ->
        if (items[i].hidden) return@forEachIndexed
        fun rowOf(split: SectionFlowSplit) = split.rows.first { i in it }
        val widthPx =
          bySplit(::rowOf) { split ->
            val row = rowOf(split)
            val total = row.sumOf { items[it].minWidth.toDouble() }.toFloat()
            val share = if (total > 0f) items[i].minWidth / total else 1f / row.size
            (sectionWidthPx - gapPx * (row.size - 1).toFloat()) * share - SeamPx.rf
          }
        val topPx =
          bySplit({ i in it.rows.first() }) { split ->
            if (i in split.rows.first()) 0f.rf else rowGapPx
          }
        RemoteBox(
          modifier =
            RemoteModifier.width(widthPx.toRemoteDp())
              .padding(0f.rdp, topPx.toRemoteDp(), 0f.rdp, 0f.rdp)
        ) {
          CompositionLocalProvider(LocalGridCellWidthPx provides widthPx) {
            RenderChild(card, snapshot, RemoteModifier.fillMaxWidth())
          }
        }
      }
    }
  }
}

/** The widest section the splits are prepared for; wider sections keep the widest split. */
private const val MaxSectionWidthDp = 2000f

/** Each card's slack, in px, so a row of shares never rounds past the section's edge. */
private const val SeamPx = 0.5f

private const val SectionWidthExpressionPrefix = "__terrazzo_section_width_"

private val WidthFormat = DecimalFormat("0")
