@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import ee.schimke.composeai.rccreation.compose.layout.RemoteAlignment
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.layout.RemoteOffset
import ee.schimke.composeai.rccreation.compose.layout.RemoteRow
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.clearAndSetSemantics
import ee.schimke.composeai.rccreation.compose.modifier.contentDescription
import ee.schimke.composeai.rccreation.compose.modifier.drawWithContent
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.state.RemoteColor
import ee.schimke.composeai.rccreation.compose.state.RemoteFloat
import ee.schimke.composeai.rccreation.compose.state.RemoteString
import ee.schimke.composeai.rccreation.compose.state.floor
import ee.schimke.composeai.rccreation.compose.state.rc
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rf
import ee.schimke.composeai.rccreation.compose.state.rs
import ee.schimke.composeai.rccreation.compose.state.rsp
import ee.schimke.composeai.rccreation.compose.text.RemoteFontFamily
import ee.schimke.composeai.rccreation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

// A rugged digital watch's LCD window: big italic segment hours and minutes in the watch's own
// Digital-7 face, with every unlit segment ghosted behind them ("88:88"), small seconds in the
// accent sitting on the baseline, all in a recessed window over a fine mesh. Colours come from the
// theme — the window is the dashboard colour, lit segments the primary text, seconds the accent —
// so the positive (light) display is
// black segments on grey and the negative (dark) one white on black. Every digit reads the
// player's clock, so the face ticks without a re-encode.

/**
 * The wordmark a large LCD-window clock sets under its date when the card has no title of its own.
 * Rugged Digital is a one-off made with Greg Bullock, the designer of the GShark watch face it
 * comes from; G-SPOT is that face's riff on a sports-watch maker's mark.
 */
internal const val LcdWindowWordmark = "G-SPOT"

/**
 * The name a card gives Digital-7 Mono Italic (`RemoteFontFamily.Named`), the segment face of the
 * watch Rugged Digital comes from. Hosts resolve it to the font the app bundles
 * (`ee.schimke.ha.rc.HaFonts`).
 */
const val HaDigital7Font = "digital7"

/**
 * The name a card gives Russo One (`RemoteFontFamily.Named`): heavy, wide and squared, the nearest
 * open face to the G-SPOT wordmark's lettering, for Rugged Digital's section headings. Hosts
 * resolve it to the font the app bundles (`ee.schimke.ha.rc.HaFonts`).
 */
const val HaGSpotFont = "gspot"

/** The hours-minutes digits' size, as a share of the window's height. */
private const val MainShare = 0.86f

/** A Digital-7 line's height, as a share of its font size: the whole glyph, foot included. */
private const val GlyphLine = 1.25f

/** The seconds' size, as a share of the hours-minutes digits'. */
private const val SecondsShare = 0.46f

/**
 * The time in an LCD window: Digital-7 digits over their ghosts, accent seconds, in a window sized
 * so its hours and minutes are [digitSp] tall: the caller's time size, which a launcher widget has
 * already fitted to its width.
 */
@Composable
@RemoteComposable
internal fun LcdWindowClock(data: HaClockData, timeLabel: RemoteString, digitSp: Int) {
  // The window's height, from which its padding is measured.
  val heightDp = digitSp / MainShare
  val frozen = data.staticTimeLabel?.let { parseClockLabel(it, data.use24Hour) }
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
  val theme = haTheme()
  val offset =
    if (data.zoned) cardFloat("zone_offset_minutes", data.zoneOffsetMinutes.toFloat()) else 0f.rf
  val total =
    RemoteFloat(RcProtocol.FLOAT_TIME_IN_HR) * 60f +
      RemoteFloat(RcProtocol.FLOAT_TIME_IN_MIN).rem(60f) +
      offset +
      1_440_000f.rf
  val hour24 = frozen?.hour?.toFloat()?.rf ?: floor(total.rem(1440f) / 60f)
  val minute = frozen?.minute?.toFloat()?.rf ?: total.rem(60f)
  val second = frozen?.second?.toFloat()?.rf ?: RemoteFloat(RcProtocol.FLOAT_TIME_IN_SEC).rem(60f)
  val hour =
    if (data.use24Hour) hour24 else hour24.rem(12f).isEqualTo(0f.rf).select(12f.rf, hour24.rem(12f))
  val time = hour.toPaddedRemoteString(2) + ":" + minute.toPaddedRemoteString(2)
  val secs = second.toPaddedRemoteString(2)
  // Under the kit's own palette, ghosts are opaque blends over the window. On a host's surfaces (a
  // launcher widget in system colours) the window and digits are the host's runtime roles, so the
  // ghosts are translucent copies of those roles and follow the host's colours too.
  val active = currentHaUberTheme()
  val palette = active.theme.palette(active.darkTheme)
  val lit = theme.primaryText
  val accent = theme.linkText
  val ghost =
    if (active.ownPalette) lerp(palette.dashboardBackground, palette.primaryText, 0.12f).rc
    else theme.primaryText.copy(alpha = 0.12f.rf)
  val accentGhost =
    if (active.ownPalette) lerp(palette.dashboardBackground, palette.linkText, 0.18f).rc
    else theme.linkText.copy(alpha = 0.18f.rf)
  // Digital-7 Mono Italic: every glyph, the colon included, is one width, so "88:88" sits exactly
  // under any time as its unlit segments.
  val mainPx = digitSp
  val smallPx = (digitSp * SecondsShare).toInt()
  // A launcher widget is played by the system, which can't load the app's bundled Digital-7: there
  // the digits fall back to the theme's monospace and the ghosts are left out, since "88:88" lines
  // up under the time only in Digital-7's single glyph width. A capture a bitmap-font filter will
  // draw keeps Digital-7 ([LocalWidgetCardFonts]): its digits stand taller than their line, and the
  // glyphs are drawn whole (rc-players 2.27).
  val bundledFont = !widgetDropsCardFonts()
  val themeStyle = haTextStyle()
  // Digital-7's digits stand taller than the player's default font, which sizes the text's box;
  // a line height of the full glyph keeps their feet inside it.
  fun segments(size: Int) =
    if (bundledFont)
      RemoteTextStyle.Default.merge(
        RemoteTextStyle(
          fontFamily = RemoteFontFamily.Named(HaDigital7Font),
          lineHeight = (size * GlyphLine).toInt().rsp,
        ),
      )
    else themeStyle
  val mainSize = mainPx.rsp
  val smallSize = smallPx.rsp
  val shadow = Color.Black.copy(alpha = if (palette.isDark) 0.28f else 0.1f).rc
  RemoteColumn(horizontalAlignment = RemoteAlignment.CenterHorizontally) {
    RemoteRow(
      modifier =
        // One label for the time: the digits and their ghost "88:88" stay out of the reader.
        RemoteModifier.clearAndSetSemantics { contentDescription = timeLabel }
          .drawWithContent {
            // The window: a bevel in the divider, the recess in the dashboard colour, a shadow
            // under its top edge, and a fine mesh behind the glass.
            val r = height * 0.1f.rf
            fillRound(theme.divider, 0f.rf, 0f.rf, width, height, r)
            val inset = height * 0.025f.rf
            fillRound(
              theme.dashboardBackground,
              inset,
              inset,
              width - inset * 2f.rf,
              height - inset * 2f.rf,
              r * 0.8f.rf,
            )
            fillRound(shadow, r, inset, width - r * 2f.rf, height * 0.03f.rf, height * 0.015f.rf)
            val mesh = fillPaint(theme.divider.copy(alpha = 0.55f.rf))
            val rows = 9
            val cols = 36
            for (row in 0 until rows) for (col in 0 until cols) {
              val stagger = if (row % 2 == 0) 0f else 0.5f
              drawCircle(
                mesh,
                height * 0.012f.rf,
                RemoteOffset(
                  width * ((col + 0.5f + stagger) / (cols + 0.5f)).rf,
                  height * ((row + 0.75f) / (rows + 0.5f)).rf,
                ),
              )
            }
            drawContent()
          }
          .padding(
            start = (heightDp * 0.14f).rdp,
            end = (heightDp * 0.14f).rdp,
            top = (heightDp * 0.04f).rdp,
            bottom = (heightDp * 0.04f).rdp,
          ),
      verticalAlignment = RemoteAlignment.Bottom,
    ) {
      RemoteBox {
        if (bundledFont)
          RemoteText(
            text = "88:88".rs,
            color = ghost,
            fontSize = mainSize,
            style = segments(mainPx),
          )
        RemoteText(text = time, color = lit, fontSize = mainSize, style = segments(mainPx))
      }
      if (data.showSeconds) {
        RemoteBox(
          modifier =
            RemoteModifier.padding(start = (heightDp * 0.08f).rdp, bottom = (heightDp * 0.1f).rdp),
        ) {
          if (bundledFont)
            RemoteText(
              text = "88".rs,
              color = accentGhost,
              fontSize = smallSize,
              style = segments(smallPx),
            )
          RemoteText(text = secs, color = accent, fontSize = smallSize, style = segments(smallPx))
        }
      }
    }
    if (!data.use24Hour)
      RemoteText(
        text = hour24.isLessThan(12f.rf).select("AM".rs, "PM".rs),
        color = theme.primaryText,
        fontSize = HaType.Micro,
        style = haTextStyle(),
      )
  }
}

/**
 * A rugged watch's complication window as a key: a square recessed tile in the window colour, with
 * a bevelled rim, a shadow under its top edge and the same fine mesh as the time's window.
 *
 * The tile reaches [KeyOutset] past the key's bounds rather than padding them, so a caller that
 * sizes its key (a to-do checkbox, a mode segment) keeps its content at full size. A selected key
 * keeps the caller's accent fill under it: the tile draws only its rim, so the caller's foreground
 * stays legible on its own background.
 */
internal object LcdWindowKey : HaKeyRenderer {
  /** How far the tile reaches past the key, in document pixels. */
  private const val KeyOutset = 9f

  @Composable
  override fun chrome(accent: RemoteColor?, selected: Boolean?): RemoteModifier {
    val theme = haTheme()
    val dark = currentHaUberTheme().let { it.theme.palette(it.darkTheme).isDark }
    return RemoteModifier.drawWithContent {
      val o = KeyOutset.rf
      val x = 0f.rf - o
      val y = 0f.rf - o
      val w = width + o * 2f.rf
      val h = height + o * 2f.rf
      val r = 9f.rf
      if (selected == true) {
        drawRoundRect(
          strokePaint(2f, accent ?: theme.linkText, round = false),
          RemoteOffset(x + 1f.rf, y + 1f.rf),
          ee.schimke.composeai.rccreation.compose.layout.RemoteSize(w - 2f.rf, h - 2f.rf),
          RemoteOffset(r, r),
        )
      } else {
        fillRound(theme.divider, x, y, w, h, r)
        fillRound(theme.dashboardBackground, x + 1.5f.rf, y + 1.5f.rf, w - 3f.rf, h - 3f.rf, r)
        fillRound(
          Color.Black.copy(alpha = if (dark) 0.3f else 0.1f).rc,
          x + 4f.rf,
          y + 1.5f.rf,
          w - 8f.rf,
          2.5f.rf,
          1.2f.rf,
        )
        val mesh = fillPaint(theme.divider.copy(alpha = 0.6f.rf))
        val n = 7
        for (row in 0 until n) for (col in 0 until n) {
          drawCircle(
            mesh,
            0.9f.rf,
            RemoteOffset(
              x + w * ((col + if (row % 2 == 0) 0.5f else 1f) / (n + 0.5f)).rf,
              y + h * ((row + 0.6f) / (n + 0.2f)).rf,
            ),
          )
        }
      }
      drawContent()
    }
  }
}
