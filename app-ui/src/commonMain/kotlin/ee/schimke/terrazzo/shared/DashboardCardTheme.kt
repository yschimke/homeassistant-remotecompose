package ee.schimke.terrazzo.shared

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

/**
 * Host fallback chrome mirrors HaUberTheme's palettes; custom meter artwork still needs a design.
 * `classic` cards take the app shell's card colors ([LocalClassicCardScheme]) when there is one.
 */
@Composable
internal fun DashboardCardTheme(style: String, dark: Boolean, content: @Composable () -> Unit) {
  val base = if (cardStyleIsDark(style, dark)) darkColorScheme() else lightColorScheme()
  val colors = cardStylePalette(style, dark)
  val palette =
    if (colors == null) LocalClassicCardScheme.current ?: base
    else
      base.copy(
        surface = colors[0],
        surfaceContainerLow = colors[0],
        surfaceContainer = colors[0],
        surfaceContainerHigh = colors[0],
        surfaceVariant = colors[0],
        onSurface = colors[1],
        onSurfaceVariant = colors[2],
        primary = colors[1],
        onPrimary = colors[0],
        outline = colors[3],
        outlineVariant = colors[3],
      )
  val corner =
    when (style) {
      "lcd" -> 4
      "console" -> 6
      "braun" -> 18
      else -> 12
    }.dp
  MaterialTheme(
    colorScheme = palette,
    typography = cardStyleTypography(style, MaterialTheme.typography),
    shapes =
      MaterialTheme.shapes.copy(
        medium = RoundedCornerShape(corner),
        large = RoundedCornerShape(corner),
      ),
  ) {
    CompositionLocalProvider(LocalContentColor provides palette.onSurface, content = content)
  }
}
