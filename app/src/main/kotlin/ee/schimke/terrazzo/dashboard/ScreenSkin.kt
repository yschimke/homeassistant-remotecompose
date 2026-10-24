package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.remote.creation.compose.text.RemoteFontFamily
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ee.schimke.ha.rc.components.HaReadoutStyle
import ee.schimke.ha.rc.components.HaSurfaceMaterial
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.HaVisualFamily
import ee.schimke.ha.rc.components.ProvideCardStyle
import ee.schimke.ha.rc.components.cardPalette
import ee.schimke.ha.rc.components.currentHaUberTheme
import ee.schimke.ha.rc.components.haLegible
import ee.schimke.terrazzo.ui.LocalCardStyle
import ee.schimke.terrazzo.ui.LocalIsDarkTheme
import ee.schimke.terrazzo.ui.LocalThemeStyle

/**
 * How a secondary screen (the more-info sheet, a card's history) is skinned: the user's card style
 * ([HaUberTheme], [LocalCardStyle]) carried over from the dashboard's cards to the app chrome
 * around them, so a sheet opened from an LCD tile reads as part of the same LCD.
 *
 * [ProvideScreenSkin] puts the card style's palette into [MaterialTheme] (sheet, panels, text,
 * dividers, the accent of buttons and range pickers) and its typeface and tracking into the type
 * scale, so every Material part restyles without knowing about skins. What Material has no role for
 * is here: panel corners and borders, the surface material, the theme's ink, how the value is set
 * ([readout]) and how charts are drawn ([chart]).
 *
 * `classic`, the default card style, keeps the app's own Material look.
 */
@Immutable
data class ScreenSkin(
  val id: String,
  /** The sheet or screen background: the dashboard's own background. */
  val background: Color,
  /** A panel on it (chart, details): the card background. */
  val panel: Color,
  /** A tile inside a panel's row (a stat): a step from the panel toward the text. */
  val tile: Color,
  val panelCorner: Dp,
  val tileCorner: Dp,
  /** Range pickers and buttons; 0 is square. */
  val controlCorner: Dp,
  val border: BorderStroke?,
  val material: HaSurfaceMaterial,
  /** One colour every entity accent prints in, for a monochrome theme (LCD, Nixie). */
  val ink: Color?,
  val hotInk: Color?,
  val readout: HaReadoutStyle,
  val valueWeight: FontWeight,
  /** Captions set in capitals with tracking, as the plate themes label their instruments. */
  val capsLabels: Boolean,
  val chart: HistoryChartStyle,
) {
  val panelShape: Shape
    get() = RoundedCornerShape(panelCorner)

  val tileShape: Shape
    get() = RoundedCornerShape(tileCorner)

  /** A sheet's top corners: Material's 28 dp in classic, else twice the card corner, to 28 dp. */
  val sheetShape: Shape
    get() {
      val corner =
        if (id == HaUberTheme.Classic.id) 28.dp else (panelCorner * 2).coerceAtMost(28.dp)
      return RoundedCornerShape(topStart = corner, topEnd = corner)
    }

  /** A badge or pill: round, unless the style's controls are square-cornered. */
  val badgeShape: Shape
    get() = if (controlCorner >= 8.dp) RoundedCornerShape(50) else RoundedCornerShape(controlCorner)

  /** A caption as the skin sets it. */
  fun label(text: String): String = if (capsLabels) text.uppercase() else text
}

/**
 * How a history chart draws: a smooth or [stepped] trace, with or without an area [fill], a [glow]
 * under the line (Nixie's filament), a dot at the latest value, dashed gridlines; and a state
 * timeline as one bar or as lit [cells] (a segment LCD, an LED strip), with [segmentGap] between
 * runs and corners of [cornerFraction] of the bar height.
 */
@Immutable
data class HistoryChartStyle(
  val stepped: Boolean = false,
  val fill: Boolean = true,
  val lineWidth: Dp = 2.dp,
  val glow: Boolean = false,
  val endDot: Boolean = true,
  val dashedGrid: Boolean = false,
  val cells: Boolean = false,
  val segmentGap: Dp = 0.dp,
  val cornerFraction: Float = 0.25f,
) {
  companion object {
    val Default = HistoryChartStyle()
  }
}

/** The active [ScreenSkin]; [ProvideScreenSkin] sets it. */
val LocalScreenSkin = staticCompositionLocalOf<ScreenSkin?> { null }

/** The skin [ProvideScreenSkin] set, or the classic one from the current [MaterialTheme]. */
@Composable fun currentScreenSkin(): ScreenSkin = LocalScreenSkin.current ?: classicSkin()

/**
 * An entity's [accent] as the skin prints it: the theme's ink for a monochrome theme, else the
 * accent made legible on the card style's panels.
 */
@Composable
fun skinnedAccent(accent: Color): Color {
  val skin = currentScreenSkin()
  if (skin.id == HaUberTheme.Classic.id) return accent
  return skin.ink ?: haLegible(accent)
}

/**
 * Skins [content] in the user's card style ([LocalCardStyle]), or in [cardStyle] when given. See
 * [ScreenSkin].
 */
@Composable
fun ProvideScreenSkin(cardStyle: HaUberTheme? = null, content: @Composable () -> Unit) {
  val theme = cardStyle ?: LocalCardStyle.current
  if (theme.id == HaUberTheme.Classic.id) {
    val skin = classicSkin()
    CompositionLocalProvider(LocalScreenSkin provides skin, content = content)
    return
  }
  val style = LocalThemeStyle.current
  val dark = LocalIsDarkTheme.current
  val palette = remember(theme, style, dark) { theme.cardPalette(style, dark) }
  ProvideCardStyle(theme, palette, dark) {
    val active = currentHaUberTheme()
    val skin =
      remember(theme, palette, active) { skinFor(theme, palette, active.ink, active.hotInk) }
    val base = MaterialTheme.colorScheme
    val accent = active.ink ?: palette.linkText
    val tonal = accent.copy(alpha = 0.22f).compositeOver(palette.cardBackground)
    val colors =
      base.copy(
        primary = accent,
        onPrimary = palette.cardBackground,
        primaryContainer = tonal,
        onPrimaryContainer = palette.primaryText,
        secondaryContainer = tonal,
        onSecondaryContainer = palette.primaryText,
        background = palette.dashboardBackground,
        onBackground = palette.primaryText,
        surface = palette.dashboardBackground,
        onSurface = palette.primaryText,
        onSurfaceVariant = palette.secondaryText,
        surfaceContainerLowest = palette.dashboardBackground,
        surfaceContainerLow = palette.dashboardBackground,
        surfaceContainer = palette.cardBackground,
        surfaceContainerHigh = palette.cardBackground,
        surfaceContainerHighest = skin.tile,
        outline = lerp(palette.divider, palette.secondaryText, 0.5f),
        outlineVariant = palette.divider,
      )
    val family = theme.fontFamily.toComposeFamily()
    val typography = MaterialTheme.typography.skinned(family, theme.letterSpacingSp.sp)
    CompositionLocalProvider(LocalScreenSkin provides skin) {
      MaterialTheme(
        colorScheme = colors,
        typography = typography,
        shapes = MaterialTheme.shapes,
        content = content,
      )
    }
  }
}

/**
 * A panel in the skin: the card surface with the card style's corner, border and material — the
 * painted-metal sheen on Console, a recessed timber edge on IKEA.
 */
@Composable
fun SkinPanel(
  modifier: Modifier = Modifier,
  tile: Boolean = false,
  content: @Composable BoxScope.() -> Unit,
) {
  val skin = currentScreenSkin()
  val shape = if (tile) skin.tileShape else skin.panelShape
  val color = if (tile) skin.tile else skin.panel
  var m = modifier.clip(shape)
  m =
    when (skin.material) {
      HaSurfaceMaterial.PaintedMetal ->
        m.background(
          Brush.verticalGradient(
            listOf(lerp(color, Color.White, 0.10f), color, lerp(color, Color.Black, 0.12f))
          )
        )
      else -> m.background(color)
    }
  skin.border?.let { if (!tile) m = m.border(it, shape) }
  Box(m, content = content)
}

@Composable
private fun classicSkin(): ScreenSkin {
  val scheme = MaterialTheme.colorScheme
  return ScreenSkin(
    id = HaUberTheme.Classic.id,
    background = scheme.surfaceContainerLow,
    panel = scheme.surfaceContainerHigh,
    tile = scheme.surfaceContainerHighest,
    panelCorner = 16.dp,
    tileCorner = 12.dp,
    controlCorner = 50.dp,
    border = null,
    material = HaSurfaceMaterial.Flat,
    ink = null,
    hotInk = null,
    readout = HaReadoutStyle.Plain,
    valueWeight = FontWeight.Medium,
    capsLabels = false,
    chart = HistoryChartStyle.Default,
  )
}

private fun skinFor(
  theme: HaUberTheme,
  palette: HaTheme,
  ink: Color?,
  hotInk: Color?,
): ScreenSkin {
  val shape = theme.shape
  val corner = shape.cornerDp.dp
  return ScreenSkin(
    id = theme.id,
    background = palette.dashboardBackground,
    panel = palette.cardBackground,
    tile = lerp(palette.cardBackground, palette.primaryText, if (palette.isDark) 0.08f else 0.05f),
    panelCorner = corner,
    tileCorner = (corner * 0.75f).coerceAtLeast(if (shape.cornerDp == 0) 0.dp else 2.dp),
    controlCorner = shape.controlCornerDp.dp,
    border = shape.borderDp.takeIf { it > 0 }?.let { BorderStroke(it.dp, palette.divider) },
    material = shape.material,
    ink = ink,
    hotInk = hotInk,
    readout = theme.readout,
    valueWeight = theme.valueWeight,
    capsLabels = theme.readout == HaReadoutStyle.Plate || theme.visuals == HaVisualFamily.Lcars,
    chart = chartStyleFor(theme.visuals),
  )
}

/** Each visual family's chart grammar, in the spirit of its meters. */
internal fun chartStyleFor(family: HaVisualFamily): HistoryChartStyle =
  when (family) {
    // Fat capsules with a gap between runs, like M3E's progress indicators.
    HaVisualFamily.Expressive ->
      HistoryChartStyle(lineWidth = 3.dp, segmentGap = 3.dp, cornerFraction = 0.5f)
    // Hi-fi level meter: stepped trace on dashed hairlines, LED cells.
    HaVisualFamily.Trinitron ->
      HistoryChartStyle(
        stepped = true,
        fill = false,
        lineWidth = 1.5.dp,
        endDot = false,
        dashedGrid = true,
        cells = true,
        cornerFraction = 0f,
      )
    // Segment display: a chunky stepped trace and lit segments.
    HaVisualFamily.Lcd ->
      HistoryChartStyle(
        stepped = true,
        fill = false,
        lineWidth = 2.5.dp,
        endDot = false,
        cells = true,
        cornerFraction = 0f,
      )
    // Rams' hairlines: no fill, a fine line, a pointer dot.
    HaVisualFamily.Braun,
    HaVisualFamily.Minimalist ->
      HistoryChartStyle(fill = false, lineWidth = 1.5.dp, cornerFraction = 0.5f)
    // A glowing filament.
    HaVisualFamily.Nixie ->
      HistoryChartStyle(fill = false, glow = true, cells = true, cornerFraction = 0.5f)
    HaVisualFamily.Console -> HistoryChartStyle(glow = true, cornerFraction = 0.15f)
    // LCARS: stepped bars of colour with rounded ends.
    HaVisualFamily.Lcars ->
      HistoryChartStyle(stepped = true, lineWidth = 3.dp, segmentGap = 4.dp, cornerFraction = 0.5f)
    HaVisualFamily.Mushroom,
    HaVisualFamily.Bubble,
    HaVisualFamily.Cupertino -> HistoryChartStyle(segmentGap = 2.dp, cornerFraction = 0.5f)
    HaVisualFamily.Ikea -> HistoryChartStyle(cornerFraction = 0.1f)
    HaVisualFamily.Classic,
    HaVisualFamily.HomeAssistant,
    HaVisualFamily.Catppuccin,
    HaVisualFamily.Obsidian,
    HaVisualFamily.Noctis,
    HaVisualFamily.Arctic,
    HaVisualFamily.Aeon -> HistoryChartStyle.Default
  }

private fun RemoteFontFamily.toComposeFamily(): FontFamily? =
  when (this) {
    RemoteFontFamily.Monospace -> FontFamily.Monospace
    RemoteFontFamily.Serif -> FontFamily.Serif
    RemoteFontFamily.SansSerif -> FontFamily.SansSerif
    else -> null
  }

/** This type scale in [family] (unchanged when null) with [tracking] added to every style. */
private fun Typography.skinned(family: FontFamily?, tracking: TextUnit): Typography {
  fun TextStyle.s(): TextStyle =
    copy(
      fontFamily = family ?: fontFamily,
      letterSpacing = if (tracking.value == 0f) letterSpacing else tracking,
    )
  return copy(
    displayLarge = displayLarge.s(),
    displayMedium = displayMedium.s(),
    displaySmall = displaySmall.s(),
    headlineLarge = headlineLarge.s(),
    headlineMedium = headlineMedium.s(),
    headlineSmall = headlineSmall.s(),
    titleLarge = titleLarge.s(),
    titleMedium = titleMedium.s(),
    titleSmall = titleSmall.s(),
    bodyLarge = bodyLarge.s(),
    bodyMedium = bodyMedium.s(),
    bodySmall = bodySmall.s(),
    labelLarge = labelLarge.s(),
    labelMedium = labelMedium.s(),
    labelSmall = labelSmall.s(),
  )
}
