@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards.shutter

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.remote.creation.compose.capture.toRemoteImageVector
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteCanvas
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.layout.RemoteSize
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.modifier.visibility
import androidx.compose.remote.creation.compose.modifier.width
import androidx.compose.remote.creation.compose.shapes.RemoteCircleShape
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.RemoteBoolean
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteInt
import androidx.compose.remote.creation.compose.state.RemotePaint
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.ri
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.wear.compose.remote.material3.RemoteIcon
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.animateRemoteFloat
import ee.schimke.ha.rc.components.currentRemoteHaTheme
import ee.schimke.ha.rc.components.toRemoteAction

/**
 * A sectional garage door seen from the front, in its opening in the wall.
 *
 * - The **lintel** across the top is the housing the door rolls up into.
 * - The **door** hangs from the lintel and covers [CoverLive.closedFraction] of the opening; its
 *   panel grooves and the handle on its bottom rail make it read as a door rather than a filled
 *   box, and ride up with it, disappearing into the housing one by one.
 * - Below the door the **garage interior** shows through, so a raised door visibly leaves a gap:
 *   the drawing says "the door, N % open" on its own.
 * - While the door moves, an arrow in the opening points the way it is going.
 *
 * The door's height, grooves, handle and arrow all follow the cover's bindings in-document; a new
 * height tweens, and the first frame draws the current one.
 */
@Composable
@RemoteComposable
internal fun GarageDoorDrawing(cover: CoverLive, widthDp: Int = 120, heightDp: Int = 72) {
  val theme = currentRemoteHaTheme()
  val lintelDp = 6f
  val sillDp = 3f
  val jambDp = 4f
  val openingHeightDp = heightDp - lintelDp - sillDp
  // Four panels when closed: three grooves between them.
  val panelPitchDp = (openingHeightDp.toInt() / 4).coerceAtLeast(4).toFloat()
  val fraction = animateRemoteFloat(cover.closedFraction, durationSeconds = DOOR_TWEEN_SECONDS)
  val idle = cover.moving.not()
  val wall = theme.divider
  val interior = theme.sectionBackground
  val door = theme.secondaryText
  val groove = theme.cardBackground

  CoverFrame(widthDp, heightDp, lintelDp, jambDp, openingHeightDp, cover) {
    val dp = width / widthDp.toFloat()
    val left = dp * jambDp
    val top = dp * lintelDp
    val openingWidth = dp * (widthDp - 2 * jambDp)
    val openingHeight = dp * openingHeightDp
    fill(wall, 0f.rf, 0f.rf, width, height)
    fill(interior, left, top, openingWidth, openingHeight)
    val doorHeight = openingHeight * fraction
    fill(door, left, top, openingWidth, doorHeight)
    val bottom = top + doorHeight
    for (k in 1..3) {
      // Shown once the door hangs half a panel below it, as the panels it separates appear.
      val shown = doorHeight.isGreaterThanOrEqualTo(dp * (k * panelPitchDp + panelPitchDp / 2))
      fill(
        shown.select(groove, Transparent),
        left,
        bottom - dp * (k * panelPitchDp),
        openingWidth,
        dp,
      )
    }
    // The handle on the bottom rail, at rest on a door with a rail to carry it.
    val handle = idle.and(doorHeight.isGreaterThanOrEqualTo(dp * 8f))
    drawRoundRect(
      fillPaint(handle.select(groove, Transparent)),
      RemoteOffset(left + (openingWidth - dp * 16f) / 2f, bottom - dp * 6f),
      RemoteSize(dp * 16f, dp * 3f),
      RemoteOffset(dp * 2f, dp * 2f),
    )
  }
}

/**
 * A window with a roller shutter: four panes behind, the shutter's slats coming down over
 * [CoverLive.closedFraction] of it, and the motion arrow while it moves.
 */
@Composable
@RemoteComposable
internal fun ShutterWindowDrawing(cover: CoverLive, widthDp: Int = 96, heightDp: Int = 80) {
  val theme = currentRemoteHaTheme()
  val frameDp = 3f
  val innerHeightDp = heightDp - 2 * frameDp
  val slatPitchDp = 6f
  val fraction = animateRemoteFloat(cover.closedFraction, durationSeconds = DOOR_TWEEN_SECONDS)
  val frame = theme.divider
  val glass = theme.sectionBackground
  val shutter = theme.secondaryText
  val joint = theme.cardBackground

  CoverFrame(widthDp, heightDp, frameDp, frameDp, innerHeightDp, cover) {
    val dp = width / widthDp.toFloat()
    val inset = dp * frameDp
    val innerWidth = width - inset * 2f
    val innerHeight = dp * innerHeightDp
    fill(frame, 0f.rf, 0f.rf, width, height)
    fill(glass, inset, inset, innerWidth, innerHeight)
    // Window cross: one vertical and one horizontal glazing bar.
    fill(frame, inset + (innerWidth - dp * 2f) / 2f, inset, dp * 2f, innerHeight)
    fill(frame, inset, inset + (innerHeight - dp * 2f) / 2f, innerWidth, dp * 2f)
    val shutterHeight = innerHeight * fraction
    fill(shutter, inset, inset, innerWidth, shutterHeight)
    // Slat joints every slatPitchDp, counted up from the bottom edge.
    val bottom = inset + shutterHeight
    for (j in 1..(innerHeightDp / slatPitchDp).toInt()) {
      val shown = shutterHeight.isGreaterThanOrEqualTo(dp * (j * slatPitchDp + 1f))
      fill(shown.select(joint, Transparent), inset, bottom - dp * (j * slatPitchDp), innerWidth, dp)
    }
  }
}

/**
 * The drawing's canvas, rounded, with the motion arrow centred over its opening (below [topDp],
 * inset [sideDp], [openingHeightDp] tall) so it never sits on the handle.
 */
@Composable
@RemoteComposable
private fun CoverFrame(
  widthDp: Int,
  heightDp: Int,
  topDp: Float,
  sideDp: Float,
  openingHeightDp: Float,
  cover: CoverLive,
  draw: RemoteDrawScope.() -> Unit,
) {
  RemoteBox(
    modifier =
      RemoteModifier.width(widthDp.rdp).height(heightDp.rdp).clip(RemoteRoundedCornerShape(4.rdp))
  ) {
    RemoteCanvas(RemoteModifier.width(widthDp.rdp).height(heightDp.rdp)) { draw() }
    if (openingHeightDp >= 20f) {
      RemoteColumn(horizontalAlignment = RemoteAlignment.CenterHorizontally) {
        RemoteBox(RemoteModifier.width(widthDp.rdp).height(topDp.rdp))
        RemoteBox(
          modifier = RemoteModifier.width((widthDp - 2 * sideDp).rdp).height(openingHeightDp.rdp),
          contentAlignment = RemoteAlignment.Center,
        ) {
          MotionArrow(Icons.Outlined.ArrowUpward, "Opening", cover.opening)
          MotionArrow(Icons.Outlined.ArrowDownward, "Closing", cover.closing)
        }
      }
    }
  }
}

@Composable
@RemoteComposable
private fun MotionArrow(icon: ImageVector, description: String, shown: RemoteBoolean) {
  val theme = currentRemoteHaTheme()
  RemoteBox(
    modifier =
      RemoteModifier.size(22.rdp)
        .visibility(shown.visibility())
        .clip(RemoteCircleShape)
        .background(theme.cardBackground),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteIcon(
      imageVector = icon.toRemoteImageVector(),
      contentDescription = description.rs,
      modifier = RemoteModifier.size(16.rdp),
      tint = theme.primaryText,
    )
  }
}

/**
 * The cover's up / stop / down buttons. Each follows [CoverLive.controls]: a hidden button is not
 * drawn; a disabled one is greyed and its tap sends nothing, so a closed door can't be "closed"
 * again. [now] is the same decision for the current snapshot, which a launcher widget's tap needs
 * ([toRemoteAction]).
 */
@Composable
@RemoteComposable
internal fun CoverButtonColumn(
  controls: CoverControlsLive,
  now: CoverControls,
  open: HaAction,
  stop: HaAction,
  close: HaAction,
) {
  RemoteColumn(
    verticalArrangement = RemoteArrangement.spacedBy(6.rdp),
    horizontalAlignment = RemoteAlignment.CenterHorizontally,
  ) {
    CoverButton(Icons.Outlined.ArrowUpward, "Open", controls.open, now.open, open)
    CoverButton(Icons.Outlined.Stop, "Stop", controls.stop, now.stop, stop)
    CoverButton(Icons.Outlined.ArrowDownward, "Close", controls.close, now.close, close)
  }
}

@Composable
@RemoteComposable
private fun CoverButton(
  icon: ImageVector,
  description: String,
  live: CoverButtonLive,
  now: CoverButtonState,
  action: HaAction,
) {
  val theme = currentRemoteHaTheme()
  val clickable =
    action.toRemoteAction(live.enabled, now == CoverButtonState.Enabled)?.let {
      RemoteModifier.clickable(it)
    } ?: RemoteModifier
  val background =
    live.enabled.select(
      theme.divider,
      theme.divider.copy(alpha = theme.divider.alpha * 0.35f.rf),
    )
  val tint =
    live.enabled.select(
      theme.primaryText,
      theme.secondaryText.copy(alpha = theme.secondaryText.alpha * 0.45f.rf),
    )
  RemoteBox(
    modifier =
      RemoteModifier.size(32.rdp)
        .visibility(live.shown.visibility())
        .clip(RemoteCircleShape)
        .background(background)
        .then(clickable),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteIcon(
      imageVector = icon.toRemoteImageVector(),
      contentDescription = live.enabled.select(description.rs, "$description (unavailable)".rs),
      modifier = RemoteModifier.size(18.rdp),
      tint = tint,
    )
  }
}

/** A door's travel is seconds long and reported in steps: a tween joins the steps up. */
private const val DOOR_TWEEN_SECONDS = 0.6f

private val Transparent: RemoteColor = Color.Transparent.rc

// RemoteCompose component visibility (`Component.Visibility`).
private fun RemoteBoolean.visibility(): RemoteInt = select(1.ri, 0.ri)

private fun RemoteDrawScope.fill(
  color: RemoteColor,
  x: RemoteFloat,
  y: RemoteFloat,
  w: RemoteFloat,
  h: RemoteFloat,
) = drawRect(fillPaint(color), RemoteOffset(x, y), RemoteSize(w, h))

private fun fillPaint(color: RemoteColor) = RemotePaint {
  isAntiAlias = true
  style = PaintingStyle.Fill
}
  .apply { this.color = color }
