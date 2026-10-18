package ee.schimke.terrazzo.shared

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Host fallback chrome mirrors HaUberTheme's palettes; custom meter artwork still needs a design.
 * Keep it scoped to cards, so changing card style doesn't repaint navigation or the app toolbar.
 */
@Composable
internal fun DashboardCardTheme(style: String, dark: Boolean, content: @Composable () -> Unit) {
  val base = if (dark || style == "console") darkColorScheme() else lightColorScheme()
  val colors =
    when (style) {
      "lcd" ->
        if (dark) listOf(0xFF07263A, 0xFF8AF4FF, 0xFF3FA9B8, 0xFF0E3A52)
        else listOf(0xFFB9C3A2, 0xFF1C2416, 0xFF46503A, 0xFF9EA889)
      "braun" ->
        if (dark) listOf(0xFF2B2A27, 0xFFEFECE4, 0xFF9A968D, 0xFF45433E)
        else listOf(0xFFF8F6F0, 0xFF262624, 0xFF7A766E, 0xFFDAD5C9)
      "console" -> listOf(0xFF2F4A57, 0xFFF1ECDD, 0xFFB3C2C8, 0xFF1C2D35)
      else -> null
    }
  val palette =
    if (colors == null) base
    else
      base.copy(
        surface = Color(colors[0]),
        surfaceContainerLow = Color(colors[0]),
        surfaceContainer = Color(colors[0]),
        surfaceContainerHigh = Color(colors[0]),
        surfaceVariant = Color(colors[0]),
        onSurface = Color(colors[1]),
        onSurfaceVariant = Color(colors[2]),
        primary = Color(colors[1]),
        onPrimary = Color(colors[0]),
        outline = Color(colors[3]),
        outlineVariant = Color(colors[3]),
      )
  val corner =
    when (style) {
      "lcd" -> 4
      "console" -> 6
      "braun" -> 18
      else -> 12
    }.dp
  val typography =
    MaterialTheme.typography.let { type ->
      if (style == "lcd")
        type.copy(
          bodyMedium = type.bodyMedium.copy(fontFamily = FontFamily.Monospace),
          bodyLarge = type.bodyLarge.copy(fontFamily = FontFamily.Monospace),
          titleMedium = type.titleMedium.copy(fontFamily = FontFamily.Monospace),
          titleSmall = type.titleSmall.copy(fontFamily = FontFamily.Monospace),
        )
      else type
    }
  MaterialTheme(
    colorScheme = palette,
    typography = typography,
    shapes =
      MaterialTheme.shapes.copy(
        medium = RoundedCornerShape(corner),
        large = RoundedCornerShape(corner),
      ),
  ) {
    CompositionLocalProvider(LocalContentColor provides palette.onSurface, content = content)
  }
}
