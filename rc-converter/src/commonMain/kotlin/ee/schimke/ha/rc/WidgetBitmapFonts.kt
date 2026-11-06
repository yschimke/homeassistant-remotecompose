package ee.schimke.ha.rc

import ee.schimke.composeai.rcplayer.edit.RcBitmapFontGlyphs
import ee.schimke.composeai.rcplayer.edit.RcBitmapFontTexts
import ee.schimke.composeai.rcplayer.edit.RcDisplay
import ee.schimke.composeai.rcplayer.edit.RcTargetProfile
import ee.schimke.composeai.rcplayer.edit.RcTargetProfiles
import ee.schimke.composeai.rcplayer.edit.coreTextAsBitmapFonts
import ee.schimke.composeai.rcplayer.protocol.RcCoreText
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
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
 * What text a widget's card fills in later may show, for [WidgetBitmapFonts]: the filter runs on
 * the template, before its values are known.
 *
 * @property locale the language the values come in (a BCP 47 tag): its letters join the default
 *   alphanumeric set ([WidgetBitmapFonts.localeLetters]). A language whose script is too large for
 *   a bitmap font (Chinese, Japanese, Korean) or that the filter doesn't know leaves live text as
 *   text; null is the alphanumeric set alone.
 * @property chars more characters any live text may show.
 * @property values the values a binding may take, by its name (`sensor.s0.state`): that text's
 *   glyphs are exactly theirs, and its slot fits the longest. A state, a mode, an option.
 * @property maxLength the most characters a live text without [values] shows: its slot.
 */
data class LiveText(
  val locale: String? = null,
  val chars: String = "",
  val values: Map<String, List<String>> = emptyMap(),
  val maxLength: Int = WidgetBitmapFonts.DEFAULT_LIVE_LENGTH,
)

/**
 * The filter that draws a widget's card fonts (Rugged Digital's [HaDigital7Font] and [HaGSpotFont])
 * from bitmap fonts embedded in its document, so a [WidgetHost] that can't load them shows the
 * faces anyway: `rc-player-edit`'s [coreTextAsBitmapFonts] for the host's profile, each glyph drawn
 * once by a [RcTextRasterizer] that has the faces. Text in any other family is left as it was.
 *
 * Run it on a widget's template, before its values are baked. A text whose value the template fixes
 * is drawn from its own glyphs. A text the card fills in later (a title, a state, a reading) can't
 * be known, so it is drawn from the glyphs [LiveText] allows — the values its binding may take when
 * the client gives them, else letters, digits and common signs, with its locale's letters — or left
 * as text, in its theme's own face, where that set can't hold it: a script too large for a bitmap
 * font, or a sample the set doesn't cover.
 */
object WidgetBitmapFonts {
  /** The faces the filter draws: the card fonts, which no device ships. */
  val FAMILIES: Set<String> = setOf(HaDigital7Font, HaGSpotFont)

  /** What any live text can show: letters, digits, space and the signs readings and titles use. */
  val ALPHANUMERIC: String =
    ('A'..'Z').joinToString("") +
      ('a'..'z').joinToString("") +
      ('0'..'9').joinToString("") +
      " .,:;-+/%°()'&!?"

  /**
   * [document] with its card fonts drawn from bitmap fonts for [host], glyphs drawn by [rasterizer]
   * at [display]'s density, live text as [live] allows.
   */
  fun apply(
    document: RcDocument,
    host: WidgetHost,
    rasterizer: RcTextRasterizer,
    display: RcDisplay = RcDisplay.DEFAULT,
    live: LiveText = LiveText(),
  ): RcBitmapFontTexts =
    document.coreTextAsBitmapFonts(
      host.profile,
      FAMILIES,
      rasterizer,
      display,
      glyphs = liveGlyphs(document, live),
    )

  /**
   * The glyphs a text the card fills in later can show, by its component id, or null to leave it as
   * text: see [LiveText].
   */
  fun liveGlyphs(document: RcDocument, live: LiveText = LiveText()): (Int) -> RcBitmapFontGlyphs? {
    val samples = document.operations.filterIsInstance<RcTextData>().associate { it.id to it.text }
    val names =
      document.operations
        .filterIsInstance<RcNamedVariable>()
        .filter { it.type == RcNamedVariable.STRING_TYPE }
        .associate { it.id to it.name.substringAfter(':') }
    val texts =
      document.operations.filterIsInstance<RcCoreText>().associateBy { text ->
        (text.properties.firstOrNull { it.id == COMPONENT_ID } as? RcTextStyleProperty.IntValue)
          ?.value
      }
    val locale = live.locale?.let { localeLetters(it) ?: return { null } }.orEmpty()
    val default = ALPHANUMERIC + locale + live.chars
    return glyphs@{ componentId ->
      val text = texts[componentId] ?: return@glyphs null
      val sample = samples[text.textId].orEmpty()
      val values = names[text.textId]?.let { live.values[it] }?.takeIf { it.isNotEmpty() }
      if (values != null) {
        val chars = values.joinToString("").toSet().joinToString("")
        return@glyphs chars
          .takeIf { it.isNotEmpty() }
          ?.let { RcBitmapFontGlyphs(it, values.maxOf { value -> value.length }.coerceAtLeast(1)) }
      }
      // A sample the set can't show says the values won't fit it either.
      if (sample.any { it !in default }) return@glyphs null
      RcBitmapFontGlyphs(default, maxOf(live.maxLength, sample.length))
    }
  }

  /**
   * The letters [locale]'s language adds to [ALPHANUMERIC], or null when its script can't be held
   * in a bitmap font a widget can carry (Chinese, Japanese, Korean, and any the filter doesn't
   * know): the empty string for English and other languages [ALPHANUMERIC] covers.
   */
  fun localeLetters(locale: String): String? {
    val language = locale.substringBefore('-').substringBefore('_').lowercase()
    val lower = LOCALE_LETTERS[language] ?: return null
    return lower + lower.uppercase().filter { it.isLetter() && it !in lower }
  }

  /** The longest live text without enumerated values: a room's name. */
  const val DEFAULT_LIVE_LENGTH: Int = 12

  /** Lower-case letters each language adds to [ALPHANUMERIC]: its upper case is added too. */
  private val LOCALE_LETTERS: Map<String, String> =
    mapOf(
      "en" to "",
      "de" to "äöüß",
      "fr" to "àâæçéèêëîïôœùûüÿ",
      "es" to "áéíóúñü¡¿",
      "it" to "àèéìíòóù",
      "pt" to "ãõáâàçéêíóôú",
      "nl" to "ëïéèö",
      "sv" to "åäö",
      "fi" to "åäö",
      "da" to "æøå",
      "nb" to "æøå",
      "nn" to "æøå",
      "no" to "æøå",
      "is" to "áðéíóúýþæö",
      "pl" to "ąćęłńóśźż",
      "cs" to "áčďéěíňóřšťúůýž",
      "sk" to "áäčďéíĺľňóôŕšťúýž",
      "hu" to "áéíóöőúüű",
      "ro" to "ăâîșț",
      "hr" to "čćđšž",
      "sl" to "čšž",
      "tr" to "çğıöşü",
      "et" to "äöõüšž",
      "lv" to "āčēģīķļņšūž",
      "lt" to "ąčęėįšųūž",
      "ca" to "àçèéíïòóúü·",
    )

  /** `CoreText`'s component id property. */
  private const val COMPONENT_ID = 1
}
