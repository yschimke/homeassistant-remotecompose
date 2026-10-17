@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteCanvas
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.layout.RemoteSize
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * A recessed transport key or rocker with a separate, bezel-mounted pilot lamp. The caller owns the
 * icon, semantics and action. [progress] is the existing live on/off expression; null means a
 * momentary key. Geometry fits its caller's box, including the compact button and entity row.
 */
@Composable
@RemoteComposable
internal fun ConsoleControlFace(
  accent: RemoteColor,
  modifier: RemoteModifier,
  progress: RemoteFloat? = null,
) {
  RemoteCanvas(modifier = modifier) {
    val u = width.min(height)
    val x = 0f.rf
    val y = (height - u) / 2f.rf
    val keyW = width - u * 0.28f.rf
    val keyH = u * 0.82f.rf
    val top = y + u * 0.06f.rf
    val corner = u * 0.08f.rf
    fillRound(Color(0xFF0A1013).rc, x, top, keyW, keyH + u * 0.08f.rf, corner)
    // A shadow below a raised key, then its metal rim and dark moulded cap.
    drawRoundRect(
      metalPaint(top, keyH),
      RemoteOffset(x + u * 0.025f.rf, top),
      RemoteSize(keyW - u * 0.05f.rf, keyH),
      RemoteOffset(corner, corner),
    )
    val depression = (progress ?: 0f.rf) * u * 0.045f.rf
    val capTop = top + u * 0.065f.rf + depression
    val capH = keyH - u * 0.15f.rf
    drawRoundRect(
      consoleCapPaint(capTop, capH),
      RemoteOffset(x + u * 0.065f.rf, capTop),
      RemoteSize(keyW - u * 0.13f.rf, capH),
      RemoteOffset(corner * 0.65f.rf, corner * 0.65f.rf),
    )
    fillRect(
      Color(0xFF62727A).rc,
      x + u * 0.1f.rf,
      top + u * 0.09f.rf + depression,
      keyW - u * 0.2f.rf,
      u * 0.025f.rf,
    )
    if (progress != null) {
      // The moving seam makes the mechanism readable without relying on colour alone.
      val seam = top + keyH * 0.48f.rf + progress * keyH * 0.15f.rf
      fillRect(
        Color(0xFF080D10).rc,
        x + u * 0.08f.rf,
        seam,
        keyW - u * 0.16f.rf,
        u * 0.035f.rf,
      )
    }
    val lamp = RemoteOffset(width - u * 0.11f.rf, y + u * 0.33f.rf)
    drawCircle(metalPaint(y, u), u * 0.105f.rf, lamp)
    drawCircle(fillPaint(ConsoleSlot.rc), u * 0.08f.rf, lamp)
    drawCircle(fillPaint(accent), u * 0.058f.rf, lamp)
    drawCircle(
      fillPaint(Color.White.copy(alpha = 0.65f).rc),
      u * 0.018f.rf,
      RemoteOffset(lamp.x - u * 0.018f.rf, lamp.y - u * 0.02f.rf),
    )
    // Engraved I / O beside a rocker, not extra text that can clip a compact cell.
    if (progress != null) {
      fillRect(
        Color(0xFFDBE2DF).rc,
        width - u * 0.12f.rf,
        y + u * 0.53f.rf,
        u * 0.025f.rf,
        u * 0.1f.rf,
      )
      drawCircle(
        strokePaint(1f, Color(0xFFDBE2DF).rc),
        u * 0.04f.rf,
        RemoteOffset(width - u * 0.11f.rf, y + u * 0.77f.rf),
      )
    }
  }
}
