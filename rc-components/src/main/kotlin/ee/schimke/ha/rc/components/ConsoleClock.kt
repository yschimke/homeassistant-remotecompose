@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.core.RemoteContext
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteCanvas
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.contentDescription
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.semantics
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.floor
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// A..G (top, upper right, lower right, bottom, lower left, upper left, middle).
internal val ConsoleDigitSegments =
  listOf(0x3f, 0x06, 0x5b, 0x4f, 0x66, 0x6d, 0x7d, 0x07, 0x7f, 0x6f)

/** Red LED modules behind smoked glass. All digits read the player's clock, including seconds. */
@Composable
@RemoteComposable
internal fun ConsoleClock(data: HaClockData, timeLabel: RemoteString) {
  val frozen =
    data.staticTimeLabel?.let { label ->
      runCatching {
        LocalTime.parse(
          label,
          DateTimeFormatter.ofPattern(if (data.use24Hour) "HH:mm[:ss]" else "h:mm[:ss] a"),
        )
      }
        .getOrNull()
    }
  // Preserve the public static-label override even when it isn't a numeric time.
  if (data.staticTimeLabel != null && frozen == null) {
    RemoteText(
      text = timeLabel,
      color = haTheme().primaryText,
      fontSize = HaType.Display,
      style = haTextStyle(),
    )
    return
  }
  val offset =
    if (data.zoned) cardFloat("zone_offset_minutes", data.zoneOffsetMinutes.toFloat()) else 0f.rf
  val total =
    RemoteFloat(RemoteContext.FLOAT_TIME_IN_HR) * 60f +
      RemoteFloat(RemoteContext.FLOAT_TIME_IN_MIN).rem(60f) +
      offset +
      1_440_000f.rf
  val hour24 = frozen?.hour?.toFloat()?.rf ?: floor(total.rem(1440f) / 60f)
  val minute = frozen?.minute?.toFloat()?.rf ?: total.rem(60f)
  val second =
    frozen?.second?.toFloat()?.rf ?: RemoteFloat(RemoteContext.FLOAT_TIME_IN_SEC).rem(60f)
  val hour =
    if (data.use24Hour) hour24 else hour24.rem(12f).isEqualTo(0f.rf).select(12f.rf, hour24.rem(12f))
  val digits =
    listOf(floor(hour / 10f), hour.rem(10f), floor(minute / 10f), minute.rem(10f)) +
      if (data.showSeconds) listOf(floor(second / 10f), second.rem(10f)) else emptyList()
  RemoteColumn(horizontalAlignment = RemoteAlignment.CenterHorizontally) {
    RemoteCanvas(
      RemoteModifier.semantics { contentDescription = timeLabel }
        .fillMaxWidth()
        .height((if (data.isLarge) 72 else 36).rdp)
    ) {
      val box = FitBox(width, height, if (data.showSeconds) 5.4f else 3.8f)
      val x = box.left
      val y = box.top
      val w = box.width
      val h = box.height
      fillRound(Color(0xFF070B0D).rc, x, y, w, h, h * 0.12f.rf)
      fillRound(Color(0xFF536067).rc, x + 1f.rf, y + 1f.rf, w - 2f.rf, h - 2f.rf, h * 0.1f.rf)
      fillRound(
        Color(0xFF180C0E).rc,
        x + h * 0.07f.rf,
        y + h * 0.07f.rf,
        w - h * 0.14f.rf,
        h * 0.86f.rf,
        h * 0.06f.rf,
      )
      val cell = (w - h * 0.3f.rf) / (digits.size + (if (data.showSeconds) 0.6f else 0.3f)).rf
      val segs =
        listOf(
          floatArrayOf(.18f, .16f, .78f, .16f),
          floatArrayOf(.83f, .2f, .83f, .46f),
          floatArrayOf(.83f, .54f, .83f, .8f),
          floatArrayOf(.18f, .84f, .78f, .84f),
          floatArrayOf(.13f, .54f, .13f, .8f),
          floatArrayOf(.13f, .2f, .13f, .46f),
          floatArrayOf(.18f, .5f, .78f, .5f),
        )
      digits.forEachIndexed { i, digit ->
        val dx = x + h * 0.15f.rf + cell * (i + (i / 2) * 0.3f).rf
        segs.forEachIndexed { segment, line ->
          var ink = Color(0xFF3C1719).rc
          ConsoleDigitSegments.forEachIndexed { n, mask ->
            if (mask and (1 shl segment) != 0)
              ink = digit.isEqualTo(n.toFloat().rf).select(Color(0xFFFF5747).rc, ink)
          }
          val sx = dx + cell * minOf(line[0], line[2]).rf
          val sy = y + h * minOf(line[1], line[3]).rf
          val thickness = h * 0.055f.rf
          val sw = if (line[0] == line[2]) thickness else cell * (line[2] - line[0]).rf
          val sh = if (line[1] == line[3]) thickness else h * (line[3] - line[1]).rf
          fillRound(
            ink.copy(alpha = 0.16f.rf),
            sx - thickness * 0.3f.rf,
            sy - thickness * 0.3f.rf,
            sw + thickness * 0.6f.rf,
            sh + thickness * 0.6f.rf,
            thickness * 0.4f.rf,
          )
          fillRound(ink, sx, sy, sw, sh, thickness * 0.12f.rf)
        }
        if (i == 1 || (data.showSeconds && i == 3)) {
          val cx = dx + cell * 1.07f.rf
          for (cy in listOf(.36f, .64f)) drawCircle(
            fillPaint(Color(0xFFFF5747).rc),
            h * 0.027f.rf,
            androidx.compose.remote.creation.compose.layout.RemoteOffset(cx, y + h * cy.rf),
          )
        }
      }
      // Reflection along the upper edge of the smoked lens.
      fillRound(
        Color.White.copy(alpha = 0.1f).rc,
        x + h * 0.1f.rf,
        y + h * 0.09f.rf,
        w - h * 0.2f.rf,
        h * 0.035f.rf,
        h * 0.01f.rf,
      )
    }
    if (!data.use24Hour)
      RemoteText(
        text = hour24.isLessThan(12f.rf).select("AM".rs, "PM".rs),
        color = haTheme().primaryText,
        fontSize = HaType.Micro,
        style = haTextStyle(),
      )
  }
}
