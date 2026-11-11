@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import ee.schimke.composeai.rccreation.compose.text.RemoteFontWeight
import ee.schimke.composeai.rccreation.compose.text.RemoteTextOverflow
import ee.schimke.composeai.rccreation.compose.layout.RemoteAlignment
import ee.schimke.composeai.rccreation.compose.layout.RemoteArrangement
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.layout.RemoteCanvas
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.layout.RemoteOffset
import ee.schimke.composeai.rccreation.compose.layout.RemoteSize
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.background
import ee.schimke.composeai.rccreation.compose.modifier.clip
import ee.schimke.composeai.rccreation.compose.modifier.contentDescription
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxWidth
import ee.schimke.composeai.rccreation.compose.modifier.height
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.modifier.semantics
import ee.schimke.composeai.rccreation.compose.modifier.width
import ee.schimke.composeai.rccreation.compose.shapes.RemoteRoundedCornerShape
import ee.schimke.composeai.rccreation.compose.state.RemoteBoolean
import ee.schimke.composeai.rccreation.compose.state.RemoteFloat
import ee.schimke.composeai.rccreation.compose.state.RemoteString
import ee.schimke.composeai.rccreation.compose.state.asRemoteTextUnit
import ee.schimke.composeai.rccreation.compose.state.floor
import ee.schimke.composeai.rccreation.compose.state.rc
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rememberNamedRemoteImageBitmap
import ee.schimke.composeai.rccreation.compose.state.rf
import ee.schimke.composeai.rccreation.compose.state.rs
import ee.schimke.composeai.rccreation.compose.state.rsp
import ee.schimke.composeai.rccreation.compose.text.RemoteFontFamily
import ee.schimke.composeai.rccreation.compose.text.RemoteTextStyle
import ee.schimke.composeai.rccreation.compose.text.RemoteTimeDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

/**
 * `clock` card — local time text. The default path binds `RemoteTimeDefaults.defaultTimeString` so
 * the document ticks the time once a minute on the player without a re-encode round trip.
 * [HaClockData.staticTimeLabel] overrides for a frozen capture (used by previews and any host that
 * prefers snapshot-time text).
 *
 * `time_zone` / `show_seconds` overrides take a separate live path that builds the displayed
 * `RemoteString` from the player's hour/minute/second floats (`RcProtocol.FLOAT_TIME_IN_*`) — same
 * tick guarantee as the default, just with the offset and seconds baked into the formatting
 * expression.
 *
 * [compact] draws the time alone at a size a Wear Small slot holds, about 44 dp: no title or date.
 */
@Composable
@RemoteComposable
fun RemoteHaClock(
  data: HaClockData,
  modifier: RemoteModifier = RemoteModifier,
  compact: Boolean = false,
  timeSizeSp: Int? = null,
  narrow: Boolean = false,
) {
  val theme = haTheme()
  val timeSize =
    when {
      timeSizeSp != null -> timeSizeSp
      compact -> 30
      data.isLarge -> 64
      else -> 36
    }
  val face = haLayouts().clock
  val console = face == HaClockFace.LedModules
  val window = face == HaClockFace.LcdWindow
  RemoteBox(
    modifier =
      modifier
        .fillMaxWidth()
        .then(cardChrome(theme.cardBackground, theme.divider))
        // A one-column launcher widget ([narrow]) gives the time its width: 4 dp a side.
        .padding(horizontal = (if (narrow) 4 else 14).rdp, vertical = (if (compact) 2 else 12).rdp),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteColumn(
      horizontalAlignment = RemoteAlignment.CenterHorizontally,
      verticalArrangement = RemoteArrangement.spacedBy(2.rdp),
    ) {
      // On a large LCD-window clock the title is the watch's wordmark, set under the date instead;
      // without one it is the face's own, G-SPOT.
      // A launcher widget fits the clock to its fixed cell (it passes [timeSizeSp]) and budgets
      // only the title and date, so the wordmark is for cards that grow to their content.
      val wordmark = window && data.isLarge && !compact && !narrow && timeSizeSp == null
      if (data.title != null && !compact && !narrow && !wordmark) {
        RemoteText(
          text = cardTitle(data.title),
          color = theme.secondaryText,
          fontSize = HaType.Label,
          style = haTextStyle(),
          maxLines = 1,
          overflow = RemoteTextOverflow.Ellipsis,
        )
      }
      val timeText: RemoteString =
        data.staticTimeLabel?.rs
          ?: if (data.zoned || data.showSeconds || console || window) {
            liveClockTimeString(
              zoneOffsetMinutes =
                if (data.zoned) cardFloat("zone_offset_minutes", data.zoneOffsetMinutes.toFloat())
                else null,
              showSeconds = data.showSeconds,
              use24Hour = data.use24Hour,
            )
          } else {
            RemoteTimeDefaults.defaultTimeString(
              is24HourFormat =
                if (data.use24Hour) RemoteBoolean(true) else RemoteTimeDefaults.is24HourFormat(),
            )
          }
      if (console) {
        ConsoleClock(data, timeText)
      } else if (window) {
        // A size the caller fitted (a launcher widget's width budget) is the digits' size as given;
        // the card's own sizes are for light text, so the bolder LCD digits take a step up.
        LcdWindowClock(
          data,
          timeText,
          digitSp = timeSizeSp ?: (timeSize * LcdDigitStep).toInt().coerceAtLeast(24),
        )
      } else
        RemoteText(
          text = timeText,
          color = theme.primaryText,
          fontSize = timeSize.rsp,
          fontWeight = RemoteFontWeight.Light,
          style = haTextStyle(),
          maxLines = 1,
        )
      if (data.secondaryLabel != null && !compact && !narrow && window) {
        // The date sits in its own recessed window under the time, as on the watch.
        RemoteBox(
          modifier =
            RemoteModifier.padding(top = 4.rdp)
              .clip(RemoteRoundedCornerShape(6.rdp))
              .background(theme.dashboardBackground)
              .padding(horizontal = 12.rdp, vertical = 3.rdp),
        ) {
          RemoteText(
            text = cardText("date", data.secondaryLabel),
            color = theme.primaryText,
            fontSize = HaType.Body,
            fontWeight = RemoteFontWeight.Bold,
            style = haTextStyle(),
            maxLines = 1,
            overflow = RemoteTextOverflow.Ellipsis,
          )
        }
      } else if (data.secondaryLabel != null && !compact && !narrow) {
        RemoteText(
          text = cardText("date", data.secondaryLabel),
          color = theme.secondaryText,
          fontSize = HaType.Label,
          style = haTextStyle(),
          maxLines = 1,
          overflow = RemoteTextOverflow.Ellipsis,
        )
      }
      // Without a title of its own the wordmark is Greg's G-SPOT logo, as the watch draws it; a
      // host that can't decode it (a plain-JVM bake) sets the name in text instead.
      val logo = if (wordmark && data.title == null) HaBundledImages.gSpotWordmarkBitmap else null
      if (logo != null) {
        val bitmap = rememberNamedRemoteImageBitmap(name = "ha.gspot_wordmark", value = { logo })
        RemoteCanvas(
          RemoteModifier.padding(top = 4.rdp).width(200.rdp).height(48.rdp).semantics {
            contentDescription = LcdWindowWordmark.rs
          },
        ) {
          // The player draws a bitmap with the paint the last draw left set, translucent from the
          // window's ghosts and shadow: an opaque, empty rect resets it so the logo draws solid.
          drawRect(fillPaint(Color.White.rc), RemoteOffset(0f.rf, 0f.rf), RemoteSize(0f.rf, 0f.rf))
          // The canvas is the logo's own aspect (460 × 110), so it fills it exactly.
          drawScaledBitmap(
            image = bitmap,
            srcOffset = RemoteOffset(0f.rf, 0f.rf),
            srcSize = RemoteSize(logo.width.toFloat().rf, logo.height.toFloat().rf),
            dstOffset = RemoteOffset(0f.rf, 0f.rf),
            dstSize = RemoteSize(width, height),
          )
        }
      } else if (wordmark) {
        RemoteText(
          text = data.title?.let { cardTitle(it) } ?: LcdWindowWordmark.rs,
          color = theme.linkText,
          fontSize = HaType.Headline,
          fontWeight = RemoteFontWeight.Black,
          style =
            RemoteTextStyle.Default.merge(
              RemoteTextStyle(
                fontFamily = RemoteFontFamily.SansSerif,
                letterSpacing = 3.sp.asRemoteTextUnit(),
              ),
            ),
          maxLines = 1,
          overflow = RemoteTextOverflow.Ellipsis,
          modifier = RemoteModifier.padding(top = 6.rdp),
        )
      }
    }
  }
}

/** How much larger a card's LCD-window digits are than the text time it would set. */
private const val LcdDigitStep = 1.37f

/**
 * Build a live time `RemoteString` from the player's hour/minute/second floats. Mirrors the shape
 * of `RemoteTimeDefaults.defaultTimeString` but adds zone-offset shifting and an optional seconds
 * component.
 *
 * `RcProtocol.FLOAT_TIME_IN_HR` is hour-of-day; `FLOAT_TIME_IN_MIN` and `FLOAT_TIME_IN_SEC` are
 * minute / second since midnight, so the `% 60` reduces them to minute/second-of-the-current-unit.
 * To shift into [zoneOffsetMinutes] (target zone − host zone; null for none) we recompose
 * minute-of-day, add the offset, mod 1440, and re-split into hours and minutes.
 */
private fun liveClockTimeString(
  zoneOffsetMinutes: RemoteFloat?,
  showSeconds: Boolean,
  use24Hour: Boolean,
): RemoteString {
  val hostHour = RemoteFloat(RcProtocol.FLOAT_TIME_IN_HR)
  val hostMin = RemoteFloat(RcProtocol.FLOAT_TIME_IN_MIN).rem(60f)
  val sec = RemoteFloat(RcProtocol.FLOAT_TIME_IN_SEC).rem(60f)

  val (hour24, minute) =
    if (zoneOffsetMinutes == null) {
      hostHour to hostMin
    } else {
      // +1_440_000 keeps the modulo argument positive across any plausible
      // zone offset before we wrap to minutes-of-day.
      val totalMin = (hostHour * 60f + hostMin + zoneOffsetMinutes + 1_440_000f.rf).rem(1440f)
      floor(totalMin / 60f) to totalMin.rem(60f)
    }

  val hourText =
    if (use24Hour) {
      hour24.toPaddedRemoteString(2)
    } else {
      // 12-hour clock: 0→12, 1..11→same, 12→12, 13..23→1..11.
      val mod12 = hour24.rem(12f)
      mod12.isEqualTo(0f.rf).select(12f.rf, mod12).toPaddedRemoteString(1)
    }

  var text: RemoteString = hourText + ":" + minute.toPaddedRemoteString(2)
  if (showSeconds) {
    text = text + ":" + sec.toPaddedRemoteString(2)
  }
  if (!use24Hour) {
    text = text + " " + hour24.isLessThan(12f.rf).select("AM".rs, "PM".rs)
  }
  return text
}
