@file:Suppress("COMPOSE_APPLIER_CALL_MISMATCH", "RestrictedApi")
@file:OptIn(ExperimentalFlexBoxApi::class)

package ee.schimke.ha.previews

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalFlexBoxApi
import androidx.compose.foundation.layout.FlexAlignItems
import androidx.compose.foundation.layout.FlexAlignSelf
import androidx.compose.foundation.layout.FlexBasis
import androidx.compose.foundation.layout.FlexBox
import androidx.compose.foundation.layout.FlexDirection
import androidx.compose.foundation.layout.FlexJustifyContent
import androidx.compose.foundation.layout.FlexWrap
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth as remoteFillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height as remoteHeight
import androidx.compose.remote.creation.compose.modifier.width as remoteWidth
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ee.schimke.ha.rc.androidXExperimental
import ee.schimke.ha.rc.components.RemoteFlexBox
import ee.schimke.ha.rc.components.RemoteFlexBoxConfig
import ee.schimke.ha.rc.components.RemoteFlexConfig
import ee.schimke.ha.rc.components.asFlexBoxConfig
import ee.schimke.ha.rc.components.asFlexConfig
import ee.schimke.ha.rc.ui.HaEmbeddedPlayer

/**
 * `RemoteFlexBox` against the real `FlexBox`. Each line hands the **same** `RemoteFlexBoxConfig`
 * and per-item `RemoteFlexConfig` objects to androidx `FlexBox` (left, the reference, through
 * `asFlexBoxConfig()` / `asFlexConfig()`) and to `RemoteFlexBox` captured into a `.rc` document and
 * played back through [HaEmbeddedPlayer] (right). The grey cell is the available space; the darker
 * band is the box itself.
 */
private val Palette =
  listOf(
    Color(0xFF1E88E5),
    Color(0xFFE53935),
    Color(0xFF43A047),
    Color(0xFFFB8C00),
    Color(0xFF8E24AA),
  )
private val CellColor = Color(0xFFECEFF1)
private val BoxColor = Color(0xFFB0BEC5)

/** One child: a coloured block with a label. A null width or height is the content's. */
private data class Item(
  val label: String,
  val width: Int? = 28,
  val height: Int? = 24,
  val flex: RemoteFlexConfig? = null,
  val fontSize: Int = 11,
)

private class FlexCase(
  val title: String,
  val config: RemoteFlexBoxConfig,
  val items: List<Item>,
  val cellHeight: Int = 44,
  val boxHeight: Int? = null,
)

private const val PreviewWidth = 420
private const val HalfWidth = 196

@Composable
private fun FlexComparison(cases: List<FlexCase>) {
  Column(
    Modifier.fillMaxWidth().background(Color.White).padding(8.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(Modifier.fillMaxWidth()) {
      Text(
        "androidx FlexBox",
        Modifier.width(HalfWidth.dp + 8.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
      )
      Text("RemoteFlexBox · HaEmbeddedPlayer", fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
    cases.forEach { case ->
      Text(case.title, fontSize = 10.sp, color = Color.DarkGray)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(HalfWidth.dp).height(case.cellHeight.dp).background(CellColor)) {
          UpstreamFlexBox(case)
        }
        Box(Modifier.width(HalfWidth.dp).height(case.cellHeight.dp).background(CellColor)) {
          HaEmbeddedPlayer(profile = androidXExperimental) { RemoteFlexBoxCase(case) }
        }
      }
    }
  }
}

@Composable
private fun UpstreamFlexBox(case: FlexCase) {
  val boxModifier =
    Modifier.fillMaxWidth()
      .let { m -> case.boxHeight?.let { m.height(it.dp) } ?: m }
      .background(BoxColor)
  FlexBox(modifier = boxModifier, config = case.config.asFlexBoxConfig()) {
    case.items.forEachIndexed { index, item ->
      var m: Modifier = Modifier
      item.width?.let { m = m.width(it.dp) }
      item.height?.let { m = m.height(it.dp) }
      item.flex?.let { m = m.flex(it.asFlexConfig()) }
      Box(m.background(Palette[index % Palette.size]), contentAlignment = Alignment.Center) {
        Text(
          item.label,
          Modifier.wrapContentSize(),
          color = Color.White,
          fontSize = item.fontSize.sp,
        )
      }
    }
  }
}

@Composable
private fun RemoteFlexBoxCase(case: FlexCase) {
  val boxModifier =
    RemoteModifier.remoteFillMaxWidth()
      .let { m -> case.boxHeight?.let { m.remoteHeight(it.rdp) } ?: m }
      .background(BoxColor.rc)
  RemoteFlexBox(modifier = boxModifier, config = case.config) {
    case.items.forEachIndexed { index, item ->
      var m: RemoteModifier = RemoteModifier
      item.flex?.let { m = m.flex(it) }
      item.width?.let { m = m.remoteWidth(it.rdp) }
      item.height?.let { m = m.remoteHeight(it.rdp) }
      RemoteBox(
        modifier = m.background(Palette[index % Palette.size].rc),
        contentAlignment = RemoteAlignment.Center,
      ) {
        RemoteText(item.label.rs, color = Color.White.rc, fontSize = item.fontSize.rsp)
      }
    }
  }
}

private fun items(vararg labels: String) = labels.map { Item(it) }

private fun justify(value: FlexJustifyContent, gap: Boolean = false) = RemoteFlexBoxConfig {
  justifyContent(value)
  if (gap) columnGap(6.dp)
}

@Preview(name = "flexbox justifyContent", widthDp = PreviewWidth, heightDp = 520)
@Composable
fun FlexBox_JustifyContent() =
  FlexComparison(
    listOf(
      FlexCase("Start", justify(FlexJustifyContent.Start), items("1", "2", "3")),
      FlexCase(
        "Start + columnGap(6.dp)",
        justify(FlexJustifyContent.Start, gap = true),
        items("1", "2", "3"),
      ),
      FlexCase("Center", justify(FlexJustifyContent.Center), items("1", "2", "3")),
      FlexCase(
        "End + columnGap(6.dp)",
        justify(FlexJustifyContent.End, gap = true),
        items("1", "2", "3"),
      ),
      FlexCase("SpaceBetween", justify(FlexJustifyContent.SpaceBetween), items("1", "2", "3")),
      FlexCase("SpaceAround", justify(FlexJustifyContent.SpaceAround), items("1", "2", "3")),
      FlexCase("SpaceEvenly", justify(FlexJustifyContent.SpaceEvenly), items("1", "2", "3")),
    )
  )

private val mixedHeights =
  listOf(Item("a", height = 16), Item("b", height = 32), Item("c", height = 24, fontSize = 16))

private val stretched =
  listOf(Item("a", height = null), Item("b", height = 32), Item("c", height = null)).map {
    it.copy(flex = RemoteFlexConfig {})
  }

private fun align(value: FlexAlignItems) = RemoteFlexBoxConfig {
  alignItems(value)
  columnGap(6.dp)
}

@Preview(name = "flexbox alignItems", widthDp = PreviewWidth, heightDp = 470)
@Composable
fun FlexBox_AlignItems() =
  FlexComparison(
    listOf(
      FlexCase("Start (box 40.dp)", align(FlexAlignItems.Start), mixedHeights, boxHeight = 40),
      FlexCase("Center (box 40.dp)", align(FlexAlignItems.Center), mixedHeights, boxHeight = 40),
      FlexCase("End (box 40.dp)", align(FlexAlignItems.End), mixedHeights, boxHeight = 40),
      FlexCase(
        "Stretch (box 40.dp)",
        align(FlexAlignItems.Stretch),
        stretched,
        boxHeight = 40,
      ),
      FlexCase(
        "Stretch, wrap-content box: Remote fills the offered height",
        align(FlexAlignItems.Stretch),
        stretched,
      ),
      FlexCase(
        "Baseline",
        align(FlexAlignItems.Baseline),
        listOf(
            Item("small", width = null, height = null, fontSize = 10),
            Item("Large", width = null, height = null, fontSize = 20),
            Item("mid", width = null, height = null, fontSize = 14),
          )
          .map { it.copy(flex = RemoteFlexConfig {}) },
      ),
    )
  )

@Preview(name = "flexbox grow and basis", widthDp = PreviewWidth, heightDp = 400)
@Composable
fun FlexBox_GrowBasis() =
  FlexComparison(
    listOf(
      FlexCase(
        "grow 1 : 2 : 0, basis 0",
        RemoteFlexBoxConfig { columnGap(4.dp) },
        listOf(
          Item(
            "1",
            width = null,
            flex =
              RemoteFlexConfig {
                grow(1f)
                basis(0.dp)
              },
          ),
          Item(
            "2",
            width = null,
            flex =
              RemoteFlexConfig {
                grow(2f)
                basis(0.dp)
              },
          ),
          Item("fixed", width = 40),
        ),
      ),
      FlexCase(
        "grow 1 : 1, basis Auto (content widths differ)",
        RemoteFlexBoxConfig {},
        listOf(
          Item("short", width = null, flex = RemoteFlexConfig { grow(1f) }),
          Item("a much longer label", width = null, flex = RemoteFlexConfig { grow(1f) }),
        ),
      ),
      FlexCase(
        "basis Percent(0.25) / 0.5f / 64.dp",
        RemoteFlexBoxConfig {},
        listOf(
          Item("25%", width = null, flex = RemoteFlexConfig { basis(FlexBasis.Percent(0.25f)) }),
          Item("50%", width = null, flex = RemoteFlexConfig { basis(0.5f) }),
          Item("64dp", width = null, flex = RemoteFlexConfig { basis(FlexBasis.Dp(64.dp)) }),
        ),
      ),
      FlexCase(
        "RowReverse, justify Start",
        RemoteFlexBoxConfig {
          direction(FlexDirection.RowReverse)
          columnGap(4.dp)
        },
        items("1", "2", "3"),
      ),
      FlexCase(
        "alignSelf(Stretch) under alignItems(Center) (box 40.dp)",
        RemoteFlexBoxConfig {
          alignItems(FlexAlignItems.Center)
          columnGap(4.dp)
        },
        listOf(
          Item("a"),
          Item("b", height = null, flex = RemoteFlexConfig { alignSelf(FlexAlignSelf.Stretch) }),
          Item("c", height = 36),
        ),
        boxHeight = 40,
      ),
    )
  )

@Preview(name = "flexbox wrap and column", widthDp = PreviewWidth, heightDp = 780)
@Composable
fun FlexBox_WrapColumn() =
  FlexComparison(
    listOf(
      FlexCase(
        "Wrap, columnGap(6.dp)",
        RemoteFlexBoxConfig {
          wrap(FlexWrap.Wrap)
          columnGap(6.dp)
        },
        (1..9).map { Item("$it", width = 36) },
        cellHeight = 92,
      ),
      FlexCase(
        "Wrap, justify Center, alignItems Center",
        RemoteFlexBoxConfig {
          wrap(FlexWrap.Wrap)
          justifyContent(FlexJustifyContent.Center)
          alignItems(FlexAlignItems.Center)
          columnGap(6.dp)
        },
        (1..7).map { Item("$it", width = 40, height = if (it % 2 == 0) 28 else 18) },
        cellHeight = 92,
      ),
      FlexCase(
        "Wrap, gap(6.dp) — rowGap is dropped in Remote",
        RemoteFlexBoxConfig {
          wrap(FlexWrap.Wrap)
          gap(6.dp)
        },
        (1..9).map { Item("$it", width = 36) },
        cellHeight = 92,
      ),
      FlexCase(
        "Wrap, grow 1 with basis 56.dp",
        RemoteFlexBoxConfig {
          wrap(FlexWrap.Wrap)
          columnGap(4.dp)
        },
        (1..5).map {
          Item(
            "$it",
            width = null,
            flex =
              RemoteFlexConfig {
                grow(1f)
                basis(56.dp)
              },
          )
        },
        cellHeight = 92,
      ),
      FlexCase(
        "Column, alignItems Stretch over 60 / auto / 100.dp widths",
        RemoteFlexBoxConfig {
          direction(FlexDirection.Column)
          alignItems(FlexAlignItems.Stretch)
          rowGap(4.dp)
        },
        listOf(
            Item("1", width = 60, height = 18),
            Item("2", width = null, height = 18),
            Item("3", width = 100, height = 18),
          )
          .map { it.copy(flex = RemoteFlexConfig {}) },
        cellHeight = 70,
      ),
      FlexCase(
        "Column, SpaceBetween, alignItems End (box 96.dp)",
        RemoteFlexBoxConfig {
          direction(FlexDirection.Column)
          justifyContent(FlexJustifyContent.SpaceBetween)
          alignItems(FlexAlignItems.End)
        },
        listOf(
          Item("1", width = 60, height = 20),
          Item("2", width = 40, height = 20),
          Item("3", width = 80, height = 20),
        ),
        cellHeight = 100,
        boxHeight = 96,
      ),
    )
  )
