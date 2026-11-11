@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import ee.schimke.composeai.rccreation.compose.layout.remoteComponent
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.layout.RemoteStateLayout
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.state.RemoteFloat
import ee.schimke.composeai.rccreation.compose.state.RemoteState
import ee.schimke.composeai.rccreation.compose.state.rc
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rf
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ee.schimke.ha.rc.components.rememberExpressionName
import ee.schimke.ha.rc.components.toPaddedRemoteString

/**
 * A two-dimensional, single-gate breakpoint: [content] is captured twice, `roomy = true` for a cell
 * at least [minWidthDp] wide **and** [minHeightDp] tall, `false` otherwise, and the player picks
 * one from the live cell size.
 *
 * The 2-D test is one float expression, `min(w, h · minW/minH) ≥ minW`, rather than two booleans
 * joined with `.and()`: alpha010 collapses `.and()` and nested width/height state layouts to
 * `false` at playback (#224), while a single boolean off a single expression fires. This is the
 * gate `ButtonCardConverter` introduced (#352), shared so every card can send a wide-short cell
 * (Wear S) and a narrow-tall one (`1×1`) to the same compact tier.
 *
 * A launcher capture (one that provides its widget size) captures only the matching variant.
 *
 * Use [modifier] `fillMaxSize()` so the gate reads the host's cell, not the content's intrinsic
 * size.
 */
@Composable
fun RemoteMinSizeGate(
  minWidthDp: Int,
  minHeightDp: Int,
  modifier: RemoteModifier = RemoteModifier,
  content: @Composable (roomy: Boolean) -> Unit,
) {
  require(minWidthDp > 0 && minHeightDp > 0) { "RemoteMinSizeGate needs positive minimums" }

  // A launcher capture knows its size and is redone on every resize: pick the tier now, as
  // [RemoteSizeBreakpoint] does, since Android 16 does not reliably update state-layout predicates
  // driven by component dimensions.
  val widgetSize = LocalWidgetCaptureSizeDp.current
  if (widgetSize != null) {
    RemoteBox(modifier = modifier) {
      content(widgetSize.widthDp >= minWidthDp && widgetSize.heightDp >= minHeightDp)
    }
    return
  }

  val gateName = rememberExpressionName("__min_size_gate_")
  // ratio = minW/minH, so `h · ratio ≥ minW` ⇔ `h ≥ minH`.
  val ratio = minWidthDp.toFloat() / minHeightDp.toFloat()
  RemoteBox(modifier = modifier) {
    val component = remoteComponent()
    val gate =
      RemoteFloat.createNamedRemoteFloat(
        gateName,
        component.width.min(component.height * ratio.rf),
        RemoteState.Domain.User,
      )
    val roomy = gate.isGreaterThanOrEqualTo(minWidthDp.rdp.toPx())
    // Materialise the gate expression in the document — the state layout reads it via a derived
    // boolean, which alone doesn't pull the named float into the capture (#224).
    RemoteText(text = gate.toPaddedRemoteString(1), color = Color.Transparent.rc)
    RemoteStateLayout(roomy) { isRoomy -> content(isRoomy) }
  }
}
