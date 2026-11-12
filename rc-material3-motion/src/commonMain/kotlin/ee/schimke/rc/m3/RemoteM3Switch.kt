@file:Suppress("RestrictedApi")

package ee.schimke.rc.m3

import androidx.compose.runtime.Composable
import ee.schimke.composeai.rccreation.compose.layout.RemoteCanvas
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.layout.RemoteOffset
import ee.schimke.composeai.rccreation.compose.layout.RemoteSize
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.state.RemoteBoolean
import ee.schimke.composeai.rccreation.compose.state.RemoteColor
import ee.schimke.composeai.rccreation.compose.state.RemoteFloat
import ee.schimke.composeai.rccreation.compose.state.RemotePaint
import ee.schimke.composeai.rccreation.compose.state.RemotePaintingStyle
import ee.schimke.composeai.rccreation.compose.state.lerp
import ee.schimke.composeai.rccreation.compose.state.rf

/** The five colours of a Material 3 switch. */
data class RcM3SwitchColors(
  val checkedTrack: RemoteColor,
  val checkedThumb: RemoteColor,
  val uncheckedTrack: RemoteColor,
  val uncheckedThumb: RemoteColor,
  /** The unchecked track's outline; a checked track has none. */
  val uncheckedBorder: RemoteColor,
)

/**
 * A Material 3 Expressive switch bound to [checked]. The thumb grows from half the track's height
 * to three quarters as it turns on, and springs across on the scheme's `spatialFast` token, so a
 * flip bounces off the end of the track; colours follow on `effectsFast` and never overshoot.
 *
 * The switch fills [modifier]'s size (M3's own is 52 × 32 dp) and draws everything on one canvas,
 * so the moving thumb's size and position are expressions of the animated fraction. It owns no
 * action: pass a clickable modifier.
 */
@Composable
@RemoteComposable
fun RemoteM3Switch(
  checked: RemoteBoolean,
  colors: RcM3SwitchColors,
  modifier: RemoteModifier = RemoteModifier,
  motion: RcMotionScheme = currentRcMotion(),
) {
  val target = checked.select(1f.rf, 0f.rf)
  RemoteM3SwitchByProgress(
    position = target.animate(motion.spatialFast),
    colorProgress = target.animate(motion.effectsFast),
    colors = colors,
    modifier = modifier,
  )
}

/**
 * [RemoteM3Switch] at explicit fractions: [position] places and sizes the thumb, [colorProgress]
 * mixes the colours, both `0` off to `1` on. Previews freeze it here; a caller that animates its
 * own fractions passes them.
 */
@Composable
@RemoteComposable
fun RemoteM3SwitchByProgress(
  position: RemoteFloat,
  colorProgress: RemoteFloat,
  colors: RcM3SwitchColors,
  modifier: RemoteModifier = RemoteModifier,
) {
  val ambient = rcAmbient()
  // Ambient: the track's outline only, in the colour the track would fill with, and the thumb.
  val filled = lerp(colors.uncheckedTrack, colors.checkedTrack, colorProgress)
  val track = filled.copy(alpha = filled.alpha * ambient.fade)
  // The outline fades into the track as it fills, so a checked switch shows none.
  val border = lerp(colors.uncheckedBorder, colors.checkedTrack, colorProgress)
  // A checked thumb is drawn to sit on its filled track, so ambient lights it in the outline's
  // colour: a dark thumb on a black screen would hide which side it's on.
  val thumb = ambient.color(lerp(colors.uncheckedThumb, colors.checkedThumb, colorProgress), border)
  RemoteCanvas(modifier) {
    val radius = height / 2f.rf
    // M3: a 2 dp outline on a 32 dp track.
    val stroke = height / 16f.rf
    drawRoundRect(
      fill(track),
      RemoteOffset(0f.rf, 0f.rf),
      RemoteSize(width, height),
      RemoteOffset(radius, radius),
    )
    drawRoundRect(
      outline(border, stroke),
      RemoteOffset(stroke / 2f.rf, stroke / 2f.rf),
      RemoteSize(width - stroke, height - stroke),
      RemoteOffset(radius - stroke / 2f.rf, radius - stroke / 2f.rf),
    )
    // M3: a 16 dp thumb off, 24 dp on. The centre rests a half-height in from either end.
    val thumbRadius = height * (0.25f.rf + position * 0.125f.rf)
    val centreX = radius + (width - height) * position
    drawCircle(fill(thumb), thumbRadius, RemoteOffset(centreX, radius))
  }
}

private fun fill(color: RemoteColor) = RemotePaint {
  isAntiAlias = true
  style = RemotePaintingStyle.Fill
}
  .apply { this.color = color }

private fun outline(color: RemoteColor, width: RemoteFloat) = RemotePaint {
  isAntiAlias = true
  style = RemotePaintingStyle.Stroke
  strokeWidth = width
}
  .apply { this.color = color }
