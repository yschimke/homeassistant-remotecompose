@file:Suppress("RestrictedApi")

package ee.schimke.ha.cards

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ee.schimke.composeai.rccreation.compose.layout.RemoteAlignment
import ee.schimke.composeai.rccreation.compose.layout.RemoteArrangement
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.layout.RemoteCanvas
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.layout.RemoteDrawScope
import ee.schimke.composeai.rccreation.compose.layout.RemoteOffset
import ee.schimke.composeai.rccreation.compose.layout.RemoteRow
import ee.schimke.composeai.rccreation.compose.layout.RemoteSize
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.background
import ee.schimke.composeai.rccreation.compose.modifier.border
import ee.schimke.composeai.rccreation.compose.modifier.clip
import ee.schimke.composeai.rccreation.compose.modifier.height
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.modifier.size
import ee.schimke.composeai.rccreation.compose.modifier.width
import ee.schimke.composeai.rccreation.compose.shapes.RemoteCircleShape
import ee.schimke.composeai.rccreation.compose.shapes.RemoteRoundedCornerShape
import ee.schimke.composeai.rccreation.compose.state.RemoteBoolean
import ee.schimke.composeai.rccreation.compose.state.RemoteColor
import ee.schimke.composeai.rccreation.compose.state.RemoteFloat
import ee.schimke.composeai.rccreation.compose.state.RemotePaint
import ee.schimke.composeai.rccreation.compose.state.RemotePaintingStyle
import ee.schimke.composeai.rccreation.compose.state.rc
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rf
import ee.schimke.composeai.rccreation.compose.state.ri
import ee.schimke.composeai.rccreation.compose.state.rs
import ee.schimke.composeai.rccreation.compose.state.rsp
import ee.schimke.composeai.rccreation.compose.text.RemoteFontWeight
import ee.schimke.ha.model.HaLiveBindings
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.animateRemoteFloat
import ee.schimke.ha.rc.components.haMotion
import ee.schimke.ha.rc.components.toDecimalRemoteString

// ---------------------------------------------------------------------------------------------
// Device drawings for the device packs' skeuomorphic designs (docs/design/device-packs.md): the
// thing itself, not a card about it. Each follows its entity's live bindings in-document, so a
// state change redraws without a new document and the first frame shows the current one.
// ---------------------------------------------------------------------------------------------

/**
 * A sectional garage door in a brick wall, seen from the driveway: the lintel and porch light above
 * it, four panels with raised mouldings and a row of windows, the handle on its bottom rail, and
 * the dark garage with its floor showing beneath it as it lifts.
 *
 * Bound to [entityId] (a cover), the door follows `<entityId>.current_position` (0 closed … 100
 * open), or its state (`<entityId>.state_int`) when the cover reports no position, and slides there
 * smoothly; the porch light is on while it moves. [position] and [stateInt] seed the first frame
 * and draw a design canvas with no entity. The drawing is [widthDp] × [heightDp].
 */
@Composable
@RemoteComposable
fun HaCardGarageDoor(
  modifier: RemoteModifier = RemoteModifier,
  entityId: String = "",
  position: Float = 0f,
  stateInt: Int = HaLiveBindings.COVER_CLOSED,
  widthDp: Int = 240,
  heightDp: Int = 200,
) {
  val entity = entityId.ifBlank { null }
  val pos = LiveValues.namedFloat(entity, HaLiveBindings.CURRENT_POSITION, position)
  val state = LiveValues.intState(entity, stateInt)
  val moving =
    state
      .isEqualTo(HaLiveBindings.COVER_OPENING.ri)
      .or(state.isEqualTo(HaLiveBindings.COVER_CLOSING.ri))
  val hasPosition = pos.isGreaterThan(-1000f.rf)
  val clamped = -((-pos.min(100f)).min(0f))
  // 0 fully open … 1 fully closed: the position when there is one, else one per state.
  val closed =
    hasPosition.select(
      1f.rf - clamped / 100f,
      state
        .isEqualTo(HaLiveBindings.COVER_OPEN.ri)
        .select(0f.rf, state.isEqualTo(HaLiveBindings.COVER_CLOSED.ri).select(1f.rf, 0.5f.rf)),
    )
  // A door takes seconds to travel and reports in steps: the slow spatial token joins them up.
  val fraction = animateRemoteFloat(closed, haMotion().spatialSlow)
  val porchLight = moving.select(LampOn, LampOff)

  RemoteCanvas(modifier.width(widthDp.rdp).height(heightDp.rdp)) {
    val dp = width / widthDp.toFloat()
    // The wall: brick courses with staggered joints, on mortar.
    fill(Mortar, 0f.rf, 0f.rf, width, height)
    val course = 9f
    var row = 0
    var y = 0f
    while (y < heightDp) {
      val offset = if (row % 2 == 0) 0f else -11f
      var x = offset
      while (x < widthDp) {
        // Held inside the canvas: the staggered rows start and end on half bricks.
        val bx = maxOf(x + 1f, 0f)
        val bw = minOf(x + 21f, widthDp.toFloat()) - bx
        val bh = minOf(course - 2f, heightDp - (y + 1f))
        if (bw > 0f && bh > 0f) {
          fill(
            if ((row + (x / 22f).toInt()) % 3 == 0) BrickDark else Brick,
            dp * bx,
            dp * (y + 1f),
            dp * bw,
            dp * bh,
          )
        }
        x += 22f
      }
      y += course
      row++
    }
    // The opening, its lintel and the porch light above it.
    val side = 26f
    val top = 46f
    val floor = heightDp - 14f
    val openingW = widthDp - 2 * side
    val openingH = floor - top
    fill(Lintel, dp * (side - 6f), dp * (top - 10f), dp * (openingW + 12f), dp * 10f)
    val lampX = widthDp / 2f - 7f
    fill(LampHousing, dp * lampX, dp * (top - 32f), dp * 14f, dp * 16f)
    fill(porchLight, dp * (lampX + 3f), dp * (top - 29f), dp * 8f, dp * 10f)
    // The garage behind: dark back wall, then the floor running out to the driveway.
    fill(Interior, dp * side, dp * top, dp * openingW, dp * openingH)
    fill(
      InteriorFloor,
      dp * side,
      dp * (floor - openingH * 0.28f),
      dp * openingW,
      dp * (openingH * 0.28f),
    )
    fill(Driveway, 0f.rf, dp * floor, width, height - dp * floor)
    // The door: hangs from the lintel over [fraction] of the opening, its sections riding up with
    // it.
    val left = dp * side
    val doorTop = dp * top
    val doorW = dp * openingW
    val doorH = dp * openingH * fraction
    val bottom = doorTop + doorH
    fill(Door, left, doorTop, doorW, doorH)
    val sectionH = openingH / 4f
    for (k in 0 until 4) {
      // Section k, counted from the bottom rail, shows while it hangs below the lintel.
      val shown = doorH.isGreaterThanOrEqualTo(dp * (sectionH * (k + 1) - 1f))
      val sectionTop = bottom - dp * (sectionH * (k + 1))
      fill(shown.select(Groove, Clear), left, sectionTop, doorW, dp * 1.5f)
      val inset = 6f
      val panelW = (openingW - inset * 5) / 4f
      for (p in 0 until 4) {
        val px = left + dp * (inset + p * (panelW + inset))
        val py = sectionTop + dp * 4f
        val ph = dp * (sectionH - 8f)
        if (k == 3) {
          // The top section is a row of windows.
          fill(shown.select(Glass, Clear), px, py, dp * panelW, ph)
          fill(
            shown.select(GlassShine, Clear),
            px + dp * 2f,
            py + dp * 2f,
            dp * (panelW / 3f),
            dp * 2f,
          )
        } else {
          fill(shown.select(Moulding, Clear), px, py, dp * panelW, ph)
          fill(shown.select(MouldingShade, Clear), px, py + ph - dp * 1.5f, dp * panelW, dp * 1.5f)
        }
      }
    }
    // The bottom rail's seal and, while the door rests, its handle.
    fill(Seal, left, bottom - dp * 2f, doorW, dp * 2f)
    val handle = moving.not().and(doorH.isGreaterThanOrEqualTo(dp * sectionH))
    fill(
      handle.select(Handle, Clear),
      left + doorW / 2f - dp * 9f,
      bottom - dp * 9f,
      dp * 18f,
      dp * 3f,
    )
  }
}

/**
 * A plain wall thermostat: a white plate on the wall with a display showing the room temperature
 * large, the set point and mode beside it, and a pair of ▲ ▼ buttons and a status light under it,
 * as the box in most hallways looks.
 *
 * Bound to [entityId] (a climate entity) it reads `<entityId>.current_temperature` and
 * `<entityId>.target_temperature`, a dash for one the thermostat stops reporting, and the mode from
 * `<entityId>.state`; the status light is on while the thermostat is on (`<entityId>.is_on`).
 * [current], [target] and [mode] seed the first frame and draw a design canvas with no entity.
 */
@Composable
@RemoteComposable
fun HaCardWallThermostat(
  modifier: RemoteModifier = RemoteModifier,
  entityId: String = "",
  current: Float = 19.5f,
  target: Float = 21f,
  mode: String = "Heat",
  unit: String = "°C",
) {
  val entity = entityId.ifBlank { null }
  val room = LiveValues.namedFloat(entity, HaLiveBindings.CURRENT_TEMPERATURE, current)
  val setPoint = LiveValues.namedFloat(entity, HaLiveBindings.TARGET_TEMPERATURE, target)
  fun label(value: RemoteFloat, prefix: String) =
    value
      .isGreaterThan(-1000f.rf)
      .select(prefix.rs + value.toDecimalRemoteString(1) + unit.rs, "—".rs)
  val on = LiveValues.isOn(entity, true) ?: RemoteBoolean(true)

  RemoteBox(
    modifier =
      modifier
        .size(176.rdp)
        .clip(RemoteRoundedCornerShape(22.rdp))
        .background(Plate)
        .border(1.rdp, PlateEdge, RemoteRoundedCornerShape(22.rdp))
        .padding(14.rdp),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteColumn(
      horizontalAlignment = RemoteAlignment.CenterHorizontally,
      verticalArrangement = RemoteArrangement.spacedBy(10.rdp),
    ) {
      // The display: a recessed grey LCD window.
      RemoteColumn(
        modifier =
          RemoteModifier.width(140.rdp)
            .clip(RemoteRoundedCornerShape(8.rdp))
            .background(Lcd)
            .padding(horizontal = 10.rdp, vertical = 8.rdp),
        horizontalAlignment = RemoteAlignment.CenterHorizontally,
      ) {
        RemoteText(
          text = label(room, ""),
          color = LcdInk,
          fontSize = 34.rsp,
          fontWeight = RemoteFontWeight.Bold,
          maxLines = 1,
        )
        RemoteRow(horizontalArrangement = RemoteArrangement.spacedBy(8.rdp)) {
          RemoteText(
            text = label(setPoint, "Set "),
            color = LcdInk,
            fontSize = 12.rsp,
            maxLines = 1,
          )
          RemoteText(
            text = LiveValues.state(entity, mode),
            color = LcdInk,
            fontSize = 12.rsp,
            fontWeight = RemoteFontWeight.Medium,
            maxLines = 1,
          )
        }
      }
      // ▲ ▼ and the status light.
      RemoteRow(
        verticalAlignment = RemoteAlignment.CenterVertically,
        horizontalArrangement = RemoteArrangement.spacedBy(14.rdp),
      ) {
        StepKey("▼")
        RemoteBox(
          RemoteModifier.size(8.rdp)
            .clip(RemoteCircleShape)
            .background(on.select(StatusOn, StatusOff)),
        )
        StepKey("▲")
      }
    }
  }
}

@Composable
@RemoteComposable
private fun StepKey(glyph: String) {
  RemoteBox(
    modifier =
      RemoteModifier.size(32.rdp)
        .clip(RemoteCircleShape)
        .background(Key)
        .border(1.rdp, PlateEdge, RemoteCircleShape),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteText(text = glyph.rs, color = KeyInk, fontSize = 13.rsp, maxLines = 1)
  }
}

private fun RemoteDrawScope.fill(
  color: RemoteColor,
  x: RemoteFloat,
  y: RemoteFloat,
  w: RemoteFloat,
  h: RemoteFloat,
) = drawRect(fillPaint(color), RemoteOffset(x, y), RemoteSize(w, h))

private fun fillPaint(color: RemoteColor) = RemotePaint {
  isAntiAlias = true
  style = RemotePaintingStyle.Fill
}
  .apply { this.color = color }

// The garage: real materials, not theme roles, since it draws the thing itself.
private val Mortar = Color(0xFFCFC6BC).rc
private val Brick = Color(0xFFA9573F).rc
private val BrickDark = Color(0xFF8E4633).rc
private val Lintel = Color(0xFF8F8A84).rc
private val LampHousing = Color(0xFF2B2B2B).rc
private val LampOn = Color(0xFFFFD54F).rc
private val LampOff = Color(0xFF5C5C5C).rc
private val Interior = Color(0xFF1F2124).rc
private val InteriorFloor = Color(0xFF3A3D42).rc
private val Driveway = Color(0xFF8C8C88).rc
private val Door = Color(0xFFF2F1EE).rc
private val Groove = Color(0xFFBDBAB4).rc
private val Moulding = Color(0xFFE4E2DD).rc
private val MouldingShade = Color(0xFFC9C6C0).rc
private val Glass = Color(0xFF41566B).rc
private val GlassShine = Color(0xFF9FB4C7).rc
private val Seal = Color(0xFF3B3B3B).rc
private val Handle = Color(0xFF7A7873).rc
private val Clear = Color.Transparent.rc

// The thermostat: a white plastic plate with a grey LCD.
private val Plate = Color(0xFFF7F7F5).rc
private val PlateEdge = Color(0xFFD4D3CF).rc
private val Lcd = Color(0xFFB9C4B0).rc
private val LcdInk = Color(0xFF1E2A1E).rc
private val Key = Color(0xFFEDEDEA).rc
private val KeyInk = Color(0xFF4A4A48).rc
private val StatusOn = Color(0xFFE65100).rc
private val StatusOff = Color(0xFFBDBDBD).rc
