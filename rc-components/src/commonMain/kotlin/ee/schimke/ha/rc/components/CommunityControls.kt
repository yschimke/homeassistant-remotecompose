@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteCanvas
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.tween
import androidx.compose.runtime.Composable

internal val HaVisualFamily.isCommunity: Boolean
  get() =
    this in
      setOf(
        HaVisualFamily.Mushroom,
        HaVisualFamily.Bubble,
        HaVisualFamily.Minimalist,
        HaVisualFamily.Cupertino,
        HaVisualFamily.Catppuccin,
        HaVisualFamily.Obsidian,
        HaVisualFamily.Noctis,
        HaVisualFamily.Lcars,
        HaVisualFamily.Arctic,
        HaVisualFamily.Aeon,
      )

/** A styled face inside the existing switch target. Owns no action or state binding. */
@Composable
internal fun CommunitySwitch(
  progress: RemoteFloat,
  active: RemoteColor,
  inactive: RemoteColor,
  modifier: RemoteModifier,
) {
  val family = visualFamily()
  val look = meterLook(tween(inactive, active, progress), null)
  RemoteCanvas(modifier) {
    val pad = height * 0.12f.rf
    val side = height - pad * 2f.rf
    val x = pad + (width - side - pad * 2f.rf) * progress
    // The family's scale is a visual track. A full-travel thumb always distinguishes off from on,
    // including at 0 where a segmented meter intentionally has no lit cells.
    communityBar(
      family,
      look,
      progress,
      false,
      canvasHeight = height * 0.6f.rf,
      originY = height * 0.2f.rf,
    )
    when (family) {
      HaVisualFamily.Bubble -> {
        drawCircle(
          fillPaint(look.surface),
          side * 0.53f.rf,
          RemoteOffset(x + side / 2f.rf, height / 2f.rf),
        )
        drawCircle(
          strokePaint(2f, look.text),
          side * 0.42f.rf,
          RemoteOffset(x + side / 2f.rf, height / 2f.rf),
        )
      }
      HaVisualFamily.Mushroom,
      HaVisualFamily.Cupertino -> {
        fillRound(look.surface, x, pad, side, side, side / 2f.rf)
        drawCircle(
          strokePaint(1f, look.text),
          side * 0.47f.rf,
          RemoteOffset(x + side / 2f.rf, height / 2f.rf),
        )
        drawCircle(
          fillPaint(look.value),
          side * 0.17f.rf,
          RemoteOffset(x + side / 2f.rf, height / 2f.rf),
        )
      }
      HaVisualFamily.Aeon -> {
        val center = RemoteOffset(x + side / 2f.rf, height / 2f.rf)
        rotate(45f.rf, center) {
          fillRect(
            look.surface,
            center.x - side * 0.3f.rf,
            center.y - side * 0.3f.rf,
            side * 0.6f.rf,
            side * 0.6f.rf,
          )
          outlineRect(
            look.text,
            center.x - side * 0.3f.rf,
            center.y - side * 0.3f.rf,
            side * 0.6f.rf,
            side * 0.6f.rf,
            1f.rf,
          )
        }
      }
      HaVisualFamily.Noctis -> {
        drawCircle(
          fillPaint(look.surface),
          side / 2f.rf,
          RemoteOffset(x + side / 2f.rf, height / 2f.rf),
        )
        drawCircle(
          strokePaint(2f, look.text),
          side * 0.35f.rf,
          RemoteOffset(x + side / 2f.rf, height / 2f.rf),
        )
      }
      else -> {
        fillRound(
          look.surface,
          x,
          pad,
          side,
          side,
          (if (family == HaVisualFamily.Catppuccin) 4f else 1f).rf,
        )
        outlineRect(look.text, x, pad, side, side, 1f.rf)
        fillRect(
          look.value,
          x + side * 0.4f.rf,
          pad + side * 0.2f.rf,
          side * 0.2f.rf,
          side * 0.6f.rf,
        )
      }
    }
  }
}
