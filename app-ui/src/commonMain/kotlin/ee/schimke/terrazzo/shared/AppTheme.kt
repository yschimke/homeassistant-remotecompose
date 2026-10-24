package ee.schimke.terrazzo.shared

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** The card styles the live dashboard offers, by id and label. */
internal val CARD_STYLES =
  listOf("classic" to "Classic", "braun" to "Braun", "lcd" to "Lcd", "console" to "Console")

/**
 * The page's own theme when a host supplies one: Home Assistant's active theme in the panel. Colors
 * are null where the theme doesn't set them, and fall back to Home Assistant's defaults.
 */
data class HostTheme(
  val dark: Boolean,
  val primary: Color? = null,
  val background: Color? = null,
  val card: Color? = null,
  val text: Color? = null,
  val secondaryText: Color? = null,
  val divider: Color? = null,
) {
  companion object {
    /** The panel's `getTheme()` JSON; colors are canvas-normalized `#rrggbb` or `rgba(…)`. */
    fun parse(json: String): HostTheme? {
      val obj = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
      fun color(name: String) = (obj[name] as? JsonPrimitive)?.contentOrNull?.let(::parseCssColor)
      return HostTheme(
        dark = (obj["dark"] as? JsonPrimitive)?.booleanOrNull ?: false,
        primary = color("primary"),
        background = color("background"),
        card = color("card"),
        text = color("text"),
        secondaryText = color("secondaryText"),
        divider = color("divider"),
      )
    }
  }
}

/** `#rgb`, `#rrggbb`, `#rrggbbaa`, `rgb(…)` or `rgba(…)`; anything else is null. */
internal fun parseCssColor(value: String): Color? {
  val text = value.trim().lowercase()
  if (text.startsWith("#")) {
    val hex = text.drop(1)
    val full =
      when (hex.length) {
        3 -> hex.map { "$it$it" }.joinToString("") + "ff"
        6 -> hex + "ff"
        8 -> hex
        else -> return null
      }
    val argb = full.toLongOrNull(16) ?: return null
    return Color(
      red = ((argb shr 24) and 0xFF).toInt(),
      green = ((argb shr 16) and 0xFF).toInt(),
      blue = ((argb shr 8) and 0xFF).toInt(),
      alpha = (argb and 0xFF).toInt(),
    )
  }
  val match = Regex("""rgba?\(([^)]*)\)""").matchEntire(text) ?: return null
  val parts = match.groupValues[1].split(',', '/', ' ').filter { it.isNotBlank() }
  if (parts.size !in 3..4) return null
  val rgb = parts.take(3).map { it.toFloatOrNull()?.coerceIn(0f, 255f) ?: return null }
  val alpha =
    parts.getOrNull(3)?.let {
      if (it.endsWith("%")) it.dropLast(1).toFloatOrNull()?.div(100f) else it.toFloatOrNull()
    } ?: 1f
  return Color(rgb[0] / 255f, rgb[1] / 255f, rgb[2] / 255f, alpha.coerceIn(0f, 1f))
}

/**
 * The card style's palette: surface, text, secondary text and outline. Null for `classic`, which
 * draws in the page's own theme.
 */
internal fun cardStylePalette(style: String, dark: Boolean): List<Color>? =
  when (style) {
    "lcd" ->
      if (dark) listOf(0xFF07263A, 0xFF8AF4FF, 0xFF3FA9B8, 0xFF0E3A52)
      else listOf(0xFFB9C3A2, 0xFF1C2416, 0xFF46503A, 0xFF9EA889)
    "braun" ->
      if (dark) listOf(0xFF2B2A27, 0xFFEFECE4, 0xFF9A968D, 0xFF45433E)
      else listOf(0xFFF8F6F0, 0xFF262624, 0xFF7A766E, 0xFFDAD5C9)
    "console" -> listOf(0xFF2F4A57, 0xFFF1ECDD, 0xFFB3C2C8, 0xFF1C2D35)
    else -> null
  }?.map(::Color)

internal fun cardStyleIsDark(style: String, dark: Boolean) = dark || style == "console"

internal fun cardStyleTypography(style: String, type: Typography): Typography =
  if (style == "lcd")
    type.copy(
      bodyMedium = type.bodyMedium.copy(fontFamily = FontFamily.Monospace),
      bodyLarge = type.bodyLarge.copy(fontFamily = FontFamily.Monospace),
      titleMedium = type.titleMedium.copy(fontFamily = FontFamily.Monospace),
      titleSmall = type.titleSmall.copy(fontFamily = FontFamily.Monospace),
      titleLarge = type.titleLarge.copy(fontFamily = FontFamily.Monospace),
      labelLarge = type.labelLarge.copy(fontFamily = FontFamily.Monospace),
      labelMedium = type.labelMedium.copy(fontFamily = FontFamily.Monospace),
    )
  else type

/**
 * What `classic` cards draw in under [AppChromeTheme]: the page's card color, so they match Home
 * Assistant's own cards rather than Material's default palette. Null outside the app shell.
 */
internal val LocalClassicCardScheme = staticCompositionLocalOf<ColorScheme?> { null }

private fun onColor(background: Color) =
  if (background.luminance() > 0.5f) Color(0xFF1C1B1F) else Color.White

/**
 * The app's chrome (navigation, app bars, menus, settings) follows the dashboard's look, so the
 * page around the cards doesn't clash with them: the selected card style's palette, or for
 * `classic`, Home Assistant's theme — the host's active one in the panel, else its default.
 */
@Composable
internal fun AppChromeTheme(
  style: String,
  dark: Boolean,
  host: HostTheme?,
  content: @Composable () -> Unit,
) {
  val palette = cardStylePalette(style, dark)
  val classicCards: ColorScheme?
  val scheme =
    if (palette == null) {
      val base = if (dark) darkColorScheme() else lightColorScheme()
      val primary = host?.primary ?: Color(0xFF03A9F4)
      val page = host?.background ?: if (dark) Color(0xFF111111) else Color(0xFFFAFAFA)
      val card =
        host?.card?.takeIf { it.alpha > 0.5f } ?: if (dark) Color(0xFF1C1C1C) else Color.White
      val text = host?.text ?: if (dark) Color(0xFFE1E1E1) else Color(0xFF212121)
      val secondary = host?.secondaryText ?: if (dark) Color(0xFF9B9B9B) else Color(0xFF727272)
      val divider =
        host?.divider?.let { lerp(page, it.copy(alpha = 1f), it.alpha) }
          ?: if (dark) Color(0xFF2E2E2E) else Color(0xFFE0E0E0)
      val chrome =
        base.copy(
          primary = primary,
          onPrimary = onColor(primary),
          primaryContainer = lerp(card, primary, 0.2f),
          onPrimaryContainer = text,
          secondaryContainer = lerp(card, primary, 0.2f),
          onSecondaryContainer = text,
          background = page,
          onBackground = text,
          surface = page,
          onSurface = text,
          surfaceVariant = lerp(page, divider, 0.5f),
          onSurfaceVariant = secondary,
          surfaceContainerLowest = card,
          surfaceContainerLow = card,
          surfaceContainer = card,
          surfaceContainerHigh = card,
          surfaceContainerHighest = lerp(card, secondary, 0.3f),
          outline = divider,
          outlineVariant = divider,
        )
      classicCards = chrome.copy(surface = card, surfaceVariant = card)
      chrome
    } else {
      val (card, text, secondary, outline) = palette
      val page = lerp(card, outline, 0.45f)
      val bar = lerp(card, outline, 0.25f)
      classicCards = null
      (if (cardStyleIsDark(style, dark)) darkColorScheme() else lightColorScheme()).copy(
        primary = text,
        onPrimary = card,
        primaryContainer = card,
        onPrimaryContainer = text,
        secondaryContainer = card,
        onSecondaryContainer = text,
        background = page,
        onBackground = text,
        surface = page,
        onSurface = text,
        surfaceVariant = bar,
        onSurfaceVariant = secondary,
        surfaceContainerLowest = card,
        surfaceContainerLow = bar,
        surfaceContainer = bar,
        surfaceContainerHigh = card,
        surfaceContainerHighest = card,
        outline = outline,
        outlineVariant = outline,
      )
    }
  MaterialTheme(
    colorScheme = scheme,
    typography = cardStyleTypography(style, MaterialTheme.typography),
  ) {
    CompositionLocalProvider(LocalClassicCardScheme provides classicCards, content = content)
  }
}
