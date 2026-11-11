@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import ee.schimke.composeai.rccreation.compose.layout.remoteComponent
import ee.schimke.composeai.rccreation.compose.layout.RemoteArrangement
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.layout.RemoteFlowRow
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxWidth
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.modifier.width
import ee.schimke.composeai.rccreation.compose.state.RemoteFloat
import ee.schimke.composeai.rccreation.compose.state.RemoteState
import ee.schimke.composeai.rccreation.compose.state.rc
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rf
import ee.schimke.composeai.rccreation.compose.state.toRemoteDp
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
import ee.schimke.ha.rc.components.toPaddedRemoteString

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
  RemoteBox(modifier = modifier.fillMaxWidth()) {
  // This box's width: the section's.
  val sectionWidthPx =
    RemoteFloat.createNamedRemoteFloat(
      name = name,
      defaultValue = remoteComponent().width,
      domain = RemoteState.Domain.User,
    )
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

    // Writes the width expression ahead of the cards that read it (it draws nothing).
    RemoteText(text = sectionWidthPx.toPaddedRemoteString(1), color = Color.Transparent.rc)
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
              .padding(0f.rdp, topPx.toRemoteDp(), 0f.rdp, 0f.rdp),
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
