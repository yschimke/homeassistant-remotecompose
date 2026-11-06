package ee.schimke.ha.rc

import ee.schimke.composeai.rcplayer.edit.RcBitmapFontGlyphs
import ee.schimke.composeai.rcplayer.edit.RcBitmapFontTexts
import ee.schimke.composeai.rcplayer.edit.RcDisplay
import ee.schimke.composeai.rcplayer.edit.RcTargetProfile
import ee.schimke.composeai.rcplayer.edit.RcTargetProfiles
import ee.schimke.composeai.rcplayer.edit.coreTextAsBitmapFonts
import ee.schimke.composeai.rcplayer.protocol.RcCoreText
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcTextStyleProperty
import ee.schimke.composeai.rcplayer.runtime.RcTextRasterizer
import ee.schimke.ha.rc.components.HaDigital7Font
import ee.schimke.ha.rc.components.HaGSpotFont

/**
 * A widget host that plays a card's document but can't load the fonts it names: the system draws
 * the widget, out of reach of the fonts the app fetches ([HaFontLibrary]). [wire] names it in a
 * request; [profile] is what its player decodes.
 */
enum class WidgetHost(val wire: String, val profile: RcTargetProfile) {
  /** A Wear widget, played by the watch's widget host. */
  Wear("wear-widgets", RcTargetProfiles.WEAR_WIDGETS),

  /** An Android 16 launcher widget. */
  Launcher("launcher-widgets", RcTargetProfiles.WIDGETS_V6);

  companion object {
    fun of(wire: String?): WidgetHost? = entries.firstOrNull { it.wire == wire }
  }
}

/**
 * The filter that draws a widget's card fonts (Rugged Digital's [HaDigital7Font] and [HaGSpotFont])
 * from bitmap fonts embedded in its document, so a [WidgetHost] that can't load them shows the
 * faces anyway: `rc-player-edit`'s [coreTextAsBitmapFonts] for the host's profile, each glyph drawn
 * once by a [RcTextRasterizer] that has the faces. Text in any other family is left as it was.
 *
 * Run it on a widget's template, before its values are baked: a text the card fills in later (a
 * reading, a state) is drawn from every printable ASCII glyph and the degree sign, in a slot sized
 * for a little more than the template's own sample value ([liveGlyphs]). A text whose value the
 * template fixes is drawn from just its own glyphs.
 */
object WidgetBitmapFonts {
  /** The faces the filter draws: the card fonts, which no device ships. */
  val FAMILIES: Set<String> = setOf(HaDigital7Font, HaGSpotFont)

  /** What a text the card fills in later can show: printable ASCII and the degree sign. */
  val LIVE_CHARS: String = (' '..'~').joinToString("") + "°"

  /**
   * [document] with its card fonts drawn from bitmap fonts for [host], glyphs drawn by [rasterizer]
   * at [display]'s density.
   */
  fun apply(
    document: RcDocument,
    host: WidgetHost,
    rasterizer: RcTextRasterizer,
    display: RcDisplay = RcDisplay.DEFAULT,
  ): RcBitmapFontTexts =
    document.coreTextAsBitmapFonts(
      host.profile,
      FAMILIES,
      rasterizer,
      display,
      glyphs = liveGlyphs(document),
    )

  /**
   * The glyphs a text the card fills in later can show, by its component id: [LIVE_CHARS], at half
   * as long again as the template's sample value, and at least [MIN_LIVE_LENGTH].
   */
  fun liveGlyphs(document: RcDocument): (Int) -> RcBitmapFontGlyphs? {
    val samples = document.operations.filterIsInstance<RcTextData>().associate { it.id to it.text }
    val lengths =
      document.operations.filterIsInstance<RcCoreText>().associate { text ->
        val componentId =
          (text.properties.firstOrNull { it.id == COMPONENT_ID } as? RcTextStyleProperty.IntValue)
            ?.value
        componentId to (samples[text.textId]?.length ?: 0)
      }
    return { componentId ->
      val sample = lengths[componentId] ?: 0
      RcBitmapFontGlyphs(LIVE_CHARS, maxOf(MIN_LIVE_LENGTH, (sample * 3 + 1) / 2))
    }
  }

  /** The shortest slot a live text gets: a temperature with its unit. */
  const val MIN_LIVE_LENGTH: Int = 6

  /** `CoreText`'s component id property. */
  private const val COMPONENT_ID = 1
}
