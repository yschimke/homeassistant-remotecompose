@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteStateLayout
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ee.schimke.ha.rc.components.rememberExpressionName
import java.text.DecimalFormat

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
  val gate =
    RemoteFloat.createNamedRemoteFloatExpression(gateName, RemoteState.Domain.User) {
      componentWidth().min(componentHeight() * ratio.rf)
    }
  val roomy = gate.isGreaterThanOrEqualTo(minWidthDp.rdp.toPx())

  RemoteBox(modifier = modifier) {
    // Materialise the gate expression in the document — the state layout reads it via a derived
    // boolean, which alone doesn't pull the named float into the capture (#224).
    RemoteText(text = gate.toRemoteString(GateFormat), color = Color.Transparent.rc)
    RemoteStateLayout(roomy) { isRoomy -> content(isRoomy) }
  }
}

private val GateFormat = DecimalFormat("0")
