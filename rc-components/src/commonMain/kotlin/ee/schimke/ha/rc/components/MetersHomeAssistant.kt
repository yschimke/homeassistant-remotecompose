@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.layout.RemoteSize
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.rf

/**
 * HA's built-in gauge: a flat-ended half annulus, with the 12:40 stroke/radius proportion of
 * ha-gauge. Filled sectors keep the thickness proportional at playback, including in widgets; the
 * inner sector clears the centre without a fixed-pixel stroke or rounded end caps.
 */
internal fun RemoteDrawScope.homeAssistantArc(
  look: MeterLook,
  fraction: RemoteFloat,
  baseline: Float,
) {
  val arc = ArcBox(width, height, 0f, baseline)
  drawArc(fillPaint(look.track), 180f.rf, 180f.rf, true, arc.topLeft, arc.size)
  drawArc(fillPaint(look.value), 180f.rf, fraction * 180f.rf, true, arc.topLeft, arc.size)
  val inner = arc.radius * (34f / 46f).rf
  drawArc(
    fillPaint(look.surface),
    180f.rf,
    180f.rf,
    true,
    RemoteOffset(arc.cx - inner, arc.baseY - inner),
    RemoteSize(inner * 2f.rf, inner * 2f.rf),
  )
}
