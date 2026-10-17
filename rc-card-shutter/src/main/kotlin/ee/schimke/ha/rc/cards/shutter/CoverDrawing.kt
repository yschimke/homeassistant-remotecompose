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
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.modifier.width
import androidx.compose.remote.creation.compose.shapes.RemoteCircleShape
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.wear.compose.remote.material3.RemoteIcon
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.currentRemoteHaTheme
import ee.schimke.ha.rc.components.toRemoteAction

/**
 * A sectional garage door seen from the front, in its opening in the wall.
 *
 * - The **lintel** across the top is the housing the door rolls up into.
 * - The **door** hangs from the lintel and covers [closedFraction] of the opening; its panel
 *   grooves and the handle on its bottom rail make it read as a door rather than a filled box.
 * - Below the door the **garage interior** shows through, so a raised door visibly leaves a gap:
 *   the drawing says "the door, N % open" on its own.
 * - While the door moves, an arrow in the opening points the way it is going.
 *
 * Every size is baked at capture; the card re-encodes on a state change (see the converters'
 * `dataSignature`).
 */
@Composable
@RemoteComposable
internal fun GarageDoorDrawing(
  closedFraction: Float,
  motion: GarageMotion,
  widthDp: Int = 120,
  heightDp: Int = 72,
) {
  val theme = currentRemoteHaTheme()
  val lintelDp = 6
  val sillDp = 3
  val jambDp = 4
  val openingWidthDp = widthDp - 2 * jambDp
  val openingHeightDp = heightDp - lintelDp - sillDp
  val doorHeightDp = (openingHeightDp * closedFraction.coerceIn(0f, 1f)).toInt()

  // Wall: the frame colour around the opening (lintel on top, jambs at the sides, sill below).
  RemoteColumn(
    modifier =
      RemoteModifier.width(widthDp.rdp)
        .height(heightDp.rdp)
        .clip(RemoteRoundedCornerShape(4.rdp))
        .background(theme.divider),
    horizontalAlignment = RemoteAlignment.CenterHorizontally,
  ) {
    RemoteBox(RemoteModifier.width(widthDp.rdp).height(lintelDp.rdp))
    // The opening: interior backdrop, the door hanging from the top, and the motion arrow centred
    // over both so it never sits on the handle.
    RemoteBox(
      modifier =
        RemoteModifier.width(openingWidthDp.rdp)
          .height(openingHeightDp.rdp)
          .background(theme.sectionBackground),
      contentAlignment = RemoteAlignment.TopCenter,
    ) {
      if (doorHeightDp > 0) {
        GarageDoorLeaf(
          widthDp = openingWidthDp,
          doorHeightDp = doorHeightDp,
          openingHeightDp = openingHeightDp,
          handle = motion == GarageMotion.Idle,
        )
      }
      MotionArrowBox(openingWidthDp, openingHeightDp, motion)
    }
  }
}

@Composable
@RemoteComposable
private fun GarageDoorLeaf(
  widthDp: Int,
  doorHeightDp: Int,
  openingHeightDp: Int,
  handle: Boolean,
) {
  val theme = currentRemoteHaTheme()
  // Four panels when closed; a raised door shows only the panels still below the lintel, so the
  // grooves keep their spacing and disappear into the housing one by one.
  val panelPitchDp = (openingHeightDp / 4).coerceAtLeast(4)
  val visibleGrooves = (doorHeightDp - panelPitchDp / 2) / panelPitchDp
  RemoteBox(
    modifier =
      RemoteModifier.width(widthDp.rdp).height(doorHeightDp.rdp).background(theme.secondaryText),
    contentAlignment = RemoteAlignment.BottomCenter,
  ) {
    // Grooves are laid out from the bottom rail up, so they ride up with the door.
    RemoteColumn(
      modifier = RemoteModifier.width(widthDp.rdp).height(doorHeightDp.rdp),
      verticalArrangement = RemoteArrangement.Bottom,
    ) {
      repeat(visibleGrooves) {
        RemoteBox(RemoteModifier.width(widthDp.rdp).height(1.rdp).background(theme.cardBackground))
        RemoteBox(RemoteModifier.width(widthDp.rdp).height((panelPitchDp - 1).rdp))
      }
    }
    // Handle on the bottom rail.
    if (handle && doorHeightDp >= 8) {
      RemoteColumn {
        RemoteBox(
          RemoteModifier.width(16.rdp)
            .height(3.rdp)
            .clip(RemoteRoundedCornerShape(2.rdp))
            .background(theme.cardBackground)
        )
        RemoteBox(RemoteModifier.height(3.rdp))
      }
    }
  }
}

@Composable
@RemoteComposable
private fun MotionArrowBox(widthDp: Int, heightDp: Int, motion: GarageMotion) {
  if (motion == GarageMotion.Idle || heightDp < 20) return
  val theme = currentRemoteHaTheme()
  val icon =
    if (motion == GarageMotion.Opening) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward
  RemoteBox(
    modifier = RemoteModifier.width(widthDp.rdp).height(heightDp.rdp),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteBox(
      modifier =
        RemoteModifier.size(22.rdp).clip(RemoteCircleShape).background(theme.cardBackground),
      contentAlignment = RemoteAlignment.Center,
    ) {
      RemoteIcon(
        imageVector = icon.toRemoteImageVector(),
        contentDescription = motion.name.rs,
        modifier = RemoteModifier.size(16.rdp),
        tint = theme.primaryText,
      )
    }
  }
}

/**
 * A window with a roller shutter: four panes behind, the shutter's slats coming down over
 * [closedFraction] of it, and the motion arrow while it moves. Replaces a plain filled rectangle
 * whose meaning wasn't evident.
 */
@Composable
@RemoteComposable
internal fun ShutterWindowDrawing(
  closedFraction: Float,
  motion: GarageMotion,
  widthDp: Int = 96,
  heightDp: Int = 80,
) {
  val theme = currentRemoteHaTheme()
  val frameDp = 3
  val innerWidthDp = widthDp - 2 * frameDp
  val innerHeightDp = heightDp - 2 * frameDp
  val shutterHeightDp = (innerHeightDp * closedFraction.coerceIn(0f, 1f)).toInt()
  val slatPitchDp = 6

  RemoteBox(
    modifier =
      RemoteModifier.width(widthDp.rdp)
        .height(heightDp.rdp)
        .clip(RemoteRoundedCornerShape(4.rdp))
        .background(theme.divider),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteBox(
      modifier =
        RemoteModifier.width(innerWidthDp.rdp)
          .height(innerHeightDp.rdp)
          .background(theme.sectionBackground),
      contentAlignment = RemoteAlignment.TopCenter,
    ) {
      // Window cross: one vertical and one horizontal glazing bar.
      RemoteBox(
        modifier = RemoteModifier.width(innerWidthDp.rdp).height(innerHeightDp.rdp),
        contentAlignment = RemoteAlignment.Center,
      ) {
        RemoteBox(RemoteModifier.width(2.rdp).height(innerHeightDp.rdp).background(theme.divider))
        RemoteBox(RemoteModifier.width(innerWidthDp.rdp).height(2.rdp).background(theme.divider))
      }
      if (shutterHeightDp > 0) {
        val slats = (shutterHeightDp - 1) / slatPitchDp
        RemoteBox(
          modifier =
            RemoteModifier.width(innerWidthDp.rdp)
              .height(shutterHeightDp.rdp)
              .background(theme.secondaryText),
          contentAlignment = RemoteAlignment.BottomCenter,
        ) {
          RemoteColumn(
            modifier = RemoteModifier.width(innerWidthDp.rdp).height(shutterHeightDp.rdp),
            verticalArrangement = RemoteArrangement.Bottom,
          ) {
            // Slat joints every slatPitchDp, counted up from the bottom edge.
            repeat(slats) {
              RemoteBox(
                RemoteModifier.width(innerWidthDp.rdp)
                  .height(1.rdp)
                  .background(theme.cardBackground)
              )
              RemoteBox(RemoteModifier.width(innerWidthDp.rdp).height((slatPitchDp - 1).rdp))
            }
          }
        }
      }
      MotionArrowBox(innerWidthDp, innerHeightDp, motion)
    }
  }
}

/**
 * The cover's up / stop / down buttons. Each follows [controls]: hidden buttons are not drawn;
 * disabled ones are greyed and carry no tap action, so a closed door can't be "closed" again.
 */
@Composable
@RemoteComposable
internal fun CoverButtonColumn(
  controls: CoverControls,
  open: HaAction,
  stop: HaAction,
  close: HaAction,
) {
  RemoteColumn(
    verticalArrangement = RemoteArrangement.spacedBy(6.rdp),
    horizontalAlignment = RemoteAlignment.CenterHorizontally,
  ) {
    CoverButton(Icons.Outlined.ArrowUpward, "Open", controls.open, open)
    CoverButton(Icons.Outlined.Stop, "Stop", controls.stop, stop)
    CoverButton(Icons.Outlined.ArrowDownward, "Close", controls.close, close)
  }
}

@Composable
@RemoteComposable
private fun CoverButton(
  icon: ImageVector,
  description: String,
  state: CoverButtonState,
  action: HaAction,
) {
  if (state == CoverButtonState.Hidden) return
  val theme = currentRemoteHaTheme()
  val enabled = state == CoverButtonState.Enabled
  val clickable =
    if (enabled) action.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
    else RemoteModifier
  val background =
    if (enabled) theme.divider else theme.divider.copy(alpha = theme.divider.alpha * 0.35f.rf)
  val tint =
    if (enabled) theme.primaryText
    else theme.secondaryText.copy(alpha = theme.secondaryText.alpha * 0.45f.rf)
  RemoteBox(
    modifier =
      RemoteModifier.size(32.rdp).clip(RemoteCircleShape).background(background).then(clickable),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteIcon(
      imageVector = icon.toRemoteImageVector(),
      contentDescription = (if (enabled) description else "$description (unavailable)").rs,
      modifier = RemoteModifier.size(18.rdp),
      tint = tint,
    )
  }
}
