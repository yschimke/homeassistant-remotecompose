@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards.shutter

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.border
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.modifier.width
import androidx.compose.remote.creation.compose.shapes.RemoteCircleShape
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.remote.material3.RemoteIcon
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.LocalHaTheme
import ee.schimke.ha.rc.components.toRemoteAction

/**
 * `custom:garage-shutter-card` card data. One row per garage door:
 * name, sectional-door visualisation, up / stop / down buttons, state
 * label. The visualisation renders a horizontal 5:3 frame (vs the
 * window shutter's near-square 6:5 frame) with horizontal panel
 * divisions on the door so it reads as garage rather than blinds.
 *
 * Like [HaShutterCardData] the [HaGarageEntryData.closedFraction] is
 * captured statically at emission time. To "play" an animation the
 * host re-emits with successive fractions; the previews in
 * `:previews/GarageShutterCardPreviews.kt` exercise the
 * 0% / 25% / 50% / 75% / 100% storyboard for both themes so each frame
 * of the open/close cycle is visually verifiable.
 */
data class HaGarageCardData(
    val title: RemoteString?,
    val entries: List<HaGarageEntryData>,
)

/**
 * One garage door inside the card.
 *
 * @property closedFraction 0f = fully open (door tucked into ceiling
 *   track), 1f = fully closed (door at floor). Matches the shutter
 *   card's convention so the two cards' fractions can share host code.
 * @property motion drives the in-frame motion hint — a chevron
 *   pointing up while opening, down while closing, hidden when idle.
 *   Indicates direction independent of the position label, which is
 *   useful for transitional states where `current_position` hasn't
 *   updated yet.
 */
data class HaGarageEntryData(
    val name: RemoteString,
    val closedFraction: Float,
    val motion: GarageMotion,
    val stateLabel: RemoteString,
    val showNameOnTop: Boolean,
    val openAction: HaAction,
    val closeAction: HaAction,
    val stopAction: HaAction,
)

/** Direction of in-progress motion, drives the motion-arrow overlay. */
enum class GarageMotion { Idle, Opening, Closing }

@Composable
@RemoteComposable
fun RemoteHaGarage(data: HaGarageCardData, modifier: RemoteModifier = RemoteModifier) {
    val theme = LocalHaTheme.current
    RemoteBox(
        modifier = modifier
            .fillMaxWidth()
            .clip(RemoteRoundedCornerShape(12.rdp))
            .background(theme.cardBackground.rc)
            .border(1.rdp, theme.divider.rc, RemoteRoundedCornerShape(12.rdp))
            .padding(horizontal = 12.rdp, vertical = 10.rdp),
    ) {
        RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(10.rdp)) {
            if (data.title != null) {
                RemoteText(
                    text = data.title,
                    color = theme.primaryText.rc,
                    fontSize = 15.rsp,
                    fontWeight = FontWeight.Medium,
                    style = RemoteTextStyle.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            data.entries.forEach { entry -> GarageEntry(entry) }
        }
    }
}

@Composable
@RemoteComposable
private fun GarageEntry(entry: HaGarageEntryData) {
    val theme = LocalHaTheme.current
    RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(4.rdp)) {
        if (entry.showNameOnTop) {
            RemoteText(
                text = entry.name,
                color = theme.primaryText.rc,
                fontSize = 13.rsp,
                fontWeight = FontWeight.Medium,
                style = RemoteTextStyle.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RemoteRow(
            verticalAlignment = RemoteAlignment.CenterVertically,
            horizontalArrangement = RemoteArrangement.spacedBy(12.rdp),
        ) {
            GarageDoorVisualisation(entry.closedFraction, entry.motion)
            GarageButtons(entry.openAction, entry.stopAction, entry.closeAction)
        }
        RemoteText(
            text = entry.stateLabel,
            color = theme.secondaryText.rc,
            fontSize = 11.rsp,
            style = RemoteTextStyle.Default,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Sectional garage door from the front. The frame holds a sky/exterior
 * background that's revealed as the door rises; the door panel is
 * bottom-anchored and shrinks as [closedFraction] decreases. Four
 * horizontal "panel" lines on the door read as a sectional residential
 * garage (vs a one-piece tilt-up).
 *
 * A motion arrow overlays the centre of the door when [motion] is
 * non-idle — chevron-up while opening, chevron-down while closing —
 * matching the host's open / close button glyphs so the user can read
 * intent without the state label.
 */
@Composable
@RemoteComposable
private fun GarageDoorVisualisation(closedFraction: Float, motion: GarageMotion) {
    val theme = LocalHaTheme.current
    // Frame: outer wall / concrete colour. Slightly warmer than the
    // window shutter frame so a side-by-side card lineup (window +
    // garage) reads as different fixtures, not the same one twice.
    val frame = if (theme.isDark) Color(0xFF3A3530) else Color(0xFFD9D2C5)
    // Sky / interior backdrop visible above the door when open.
    val sky = if (theme.isDark) Color(0xFF0F1B24) else Color(0xFFD8ECF6)
    // Door panel base. Slightly desaturated white-ish for residential.
    val door = if (theme.isDark) Color(0xFFB7B2A8) else Color(0xFFF1EDE3)
    // Panel divisions — horizontal lines splitting the door into 4
    // sections, the key visual cue that this is a sectional garage
    // door rather than a roller blind.
    val groove = if (theme.isDark) Color(0xFF6E6A5E) else Color(0xFFB8B0A0)

    val frameWidthDp = 120
    val frameHeightDp = 72
    val borderDp = 2
    val innerHeightDp = frameHeightDp - 2 * borderDp
    val innerWidthDp = frameWidthDp - 2 * borderDp

    val clamped = closedFraction.coerceIn(0f, 1f)
    val doorHeightDp = (innerHeightDp * clamped).toInt()

    RemoteBox(
        modifier = RemoteModifier
            .width(frameWidthDp.rdp)
            .height(frameHeightDp.rdp)
            .clip(RemoteRoundedCornerShape(6.rdp))
            .background(sky.rc)
            .border(borderDp.rdp, frame.rc, RemoteRoundedCornerShape(6.rdp)),
        contentAlignment = RemoteAlignment.BottomCenter,
    ) {
        if (doorHeightDp > 0) {
            RemoteBox(
                modifier = RemoteModifier
                    .width(innerWidthDp.rdp)
                    .height(doorHeightDp.rdp)
                    .background(door.rc),
                contentAlignment = RemoteAlignment.Center,
            ) {
                // Panel grooves: 3 horizontal lines split a fully-
                // closed door into 4 sections. We draw them whenever
                // the door is tall enough — at < ~25% closed there
                // isn't enough door for grooves to read so we skip.
                if (doorHeightDp >= innerHeightDp / 4) {
                    DoorPanelGrooves(
                        innerWidthDp = innerWidthDp,
                        doorHeightDp = doorHeightDp,
                        groove = groove,
                    )
                }
                MotionArrow(motion = motion)
            }
        }
    }
}

/**
 * Three horizontal grooves dividing the door into 4 panels. Spaced
 * evenly across the visible door height so the spacing scales with
 * how closed the door is — a half-closed door still shows two
 * grooves, just compressed.
 */
@Composable
@RemoteComposable
private fun DoorPanelGrooves(innerWidthDp: Int, doorHeightDp: Int, groove: Color) {
    RemoteColumn(
        modifier = RemoteModifier.width(innerWidthDp.rdp).height(doorHeightDp.rdp),
        verticalArrangement = RemoteArrangement.SpaceEvenly,
    ) {
        // Top spacer (no groove) + 3 grooves + bottom spacer = 4 panels.
        repeat(3) {
            RemoteBox(
                modifier = RemoteModifier
                    .width(innerWidthDp.rdp)
                    .height(1.rdp)
                    .background(groove.rc),
            )
        }
    }
}

@Composable
@RemoteComposable
private fun MotionArrow(motion: GarageMotion) {
    if (motion == GarageMotion.Idle) return
    val theme = LocalHaTheme.current
    val icon = when (motion) {
        GarageMotion.Opening -> Icons.Outlined.ArrowUpward
        GarageMotion.Closing -> Icons.Outlined.ArrowDownward
        GarageMotion.Idle -> return
    }
    // Tint stays in primaryText (high contrast on the door panel)
    // rather than picking up a state colour — the chevron is a
    // direction marker, not a "warning" or "active" badge.
    RemoteIcon(
        imageVector = icon,
        contentDescription = motion.name.rs,
        modifier = RemoteModifier.size(20.rdp),
        tint = theme.primaryText.rc,
    )
}

@Composable
@RemoteComposable
private fun GarageButtons(
    open: HaAction,
    stop: HaAction,
    close: HaAction,
) {
    RemoteColumn(
        verticalArrangement = RemoteArrangement.spacedBy(6.rdp),
        horizontalAlignment = RemoteAlignment.CenterHorizontally,
    ) {
        GarageButton(Icons.Outlined.ArrowUpward, "Open", open)
        GarageButton(Icons.Outlined.Stop, "Stop", stop)
        GarageButton(Icons.Outlined.ArrowDownward, "Close", close)
    }
}

@Composable
@RemoteComposable
private fun GarageButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    action: HaAction,
) {
    val theme = LocalHaTheme.current
    val clickable = action.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
    RemoteBox(
        modifier = RemoteModifier
            .size(32.rdp)
            .clip(RemoteCircleShape)
            .background(theme.divider.rc)
            .then(clickable),
        contentAlignment = RemoteAlignment.Center,
    ) {
        RemoteIcon(
            imageVector = icon,
            contentDescription = description.rs,
            modifier = RemoteModifier.size(18.rdp),
            tint = theme.primaryText.rc,
        )
    }
}
