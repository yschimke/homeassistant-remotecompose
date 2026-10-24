package ee.schimke.ha.rc

import androidx.compose.ui.graphics.Color

/**
 * A card's `color:` option, as Home Assistant resolves it (`computeCssColor` in
 * `home-assistant/frontend` `src/common/color/compute-color.ts`): one of the theme's named colours
 * (`THEME_COLORS`, plus the YAML-only `disabled`), or a CSS hex colour.
 *
 * The named colours are HA's default light-theme values from `--<name>-color` in
 * `src/resources/theme/color/color.globals.ts` (`primary` is `--ha-color-primary-40`). They are
 * data colours (component-system rule 2): fixed across palettes, like [HaStateColor].
 */
object HaNamedColor {
  /** HA's named theme colours, by the name a card config uses. */
  val palette: Map<String, Color> =
    mapOf(
      "primary" to Color(0xFF009AC7),
      "accent" to Color(0xFFFF9800),
      "red" to Color(0xFFF44336),
      "pink" to Color(0xFFE91E63),
      "purple" to Color(0xFF926BC7),
      "deep-purple" to Color(0xFF6E41AB),
      "indigo" to Color(0xFF3F51B5),
      "blue" to Color(0xFF2196F3),
      "light-blue" to Color(0xFF03A9F4),
      "cyan" to Color(0xFF00BCD4),
      "teal" to Color(0xFF009688),
      "green" to Color(0xFF4CAF50),
      "light-green" to Color(0xFF8BC34A),
      "lime" to Color(0xFFCDDC39),
      "yellow" to Color(0xFFFFEB3B),
      "amber" to Color(0xFFFFC107),
      "orange" to Color(0xFFFF9800),
      "deep-orange" to Color(0xFFFF6F22),
      "brown" to Color(0xFF795548),
      "light-grey" to Color(0xFFBDBDBD),
      "grey" to Color(0xFF9E9E9E),
      "dark-grey" to Color(0xFF606060),
      "blue-grey" to Color(0xFF607D8B),
      "black" to Color(0xFF000000),
      "white" to Color(0xFFFFFFFF),
      "disabled" to Color(0xFFBDBDBD),
    )

  /**
   * The colour [value] names, or null for no override: absent, blank, `state` / `default` (the
   * entity's own state colour, as HA's colour picker offers them) or a value this can't parse (HA
   * would hand an unknown CSS name to the browser; here the state colour stays).
   */
  fun resolve(value: String?): Color? {
    val key = value?.trim()?.lowercase()?.replace('_', '-') ?: return null
    if (key.isEmpty() || key == "state" || key == "default" || key == "none") return null
    palette[key]?.let {
      return it
    }
    return parseHex(key)
  }

  /** `#rgb` or `#rrggbb`, as HA's `isValidColorString` accepts. */
  private fun parseHex(value: String): Color? {
    val hex = value.removePrefix("#").takeIf { value.startsWith("#") } ?: return null
    if (!hex.all { it in '0'..'9' || it in 'a'..'f' }) return null
    val rgb =
      when (hex.length) {
        3 -> hex.map { "$it$it" }.joinToString("")
        6 -> hex
        else -> return null
      }
    return Color(0xFF000000 or rgb.toLong(16))
  }
}
