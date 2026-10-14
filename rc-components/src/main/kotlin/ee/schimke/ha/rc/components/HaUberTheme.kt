@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.shapes.RemoteCircleShape
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.shapes.RemoteShape
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteFontFamily
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme

// ---------------------------------------------------------------------------------------------
// Uber themes: a whole look, not a palette. A palette (ThemeStyle / HaTheme) recolours the chrome;
// an uber theme also decides the shape of a card, the typeface, and *how a meter is drawn* —
// smooth or segmented, hairline or chunky, glowing, with M3 Expressive's track gap — and may ink
// every reading in one colour, as a segment LCD or a Nixie tube does.
//
// Every part reads the active uber theme through [LocalHaUberTheme]; [HaUberTheme.Classic] (the
// default) is exactly the look the cards had before uber themes, so nothing changes until a host
// provides another one. See docs/architecture/card-component-system.md §6.
// ---------------------------------------------------------------------------------------------

/**
 * The family of visuals a theme draws its meters with. A family is a different *design* of each
 * Layer 2 visual, not a restyle of one drawing: an LCD thermometer is a framed column of segments
 * with no bulb, a Trinitron gauge is a VU meter with a needle, a Braun thermometer is a tuning
 * scale with a pointer. Each family lives in its own `Meters<Family>.kt`.
 */
enum class HaVisualFamily {
  /** The cards' own visuals: liquid thermometer, outlined battery, smooth arc and bar. */
  Classic,
  /** Material 3 Expressive: fat capsules with a track gap and stop dot, no bulb. */
  Expressive,
  /** Sony hi-fi: a needle VU meter, LED level columns and strips with a red peak zone. */
  Trinitron,
  /** A segment LCD: framed segment columns with ghosts and a hot ink, a phone battery. */
  Lcd,
  /** Braun: a round dial with index ticks and a pointer, tuning-scale thermometer and bar. */
  Braun,
  /** Nixie: IN-9 bargraph tubes — glass capsules with a glowing column — and a glowing arc. */
  Nixie,
  /**
   * Console: a 1970s broadcast desk, drawn skeuomorphically — a backlit VU meter behind glass, a
   * fader in a recessed slot, a lamp-lit battery in a metal bezel, a dotted lamp ring round a dial.
   */
  Console,
}

/** How a reading's value text is set in the measurement presentations. */
enum class HaReadoutStyle {
  /** The value as plain text. */
  Plain,
  /** The value on a tonal pill (Material 3 Expressive). */
  TonalPill,
  /** A tracked caption label over the value, ruled with a hairline (Trinitron). */
  Plate,
  /** The value in a recessed screen panel (LCD). */
  Screen,
}

/**
 * Card and control shape. Corners and border in dp; a border of 0 draws none. [controlCornerDp] is
 * the toggle switch's track corner, and [squareKnob] gives it a square knob instead of a round one.
 * [material] is what the surfaces are made of.
 */
data class HaCardShapeStyle(
  val cornerDp: Int = HaShape.Card,
  val borderDp: Int = 1,
  val controlCornerDp: Int = 11,
  val squareKnob: Boolean = false,
  val material: HaSurfaceMaterial = HaSurfaceMaterial.Flat,
)

/** What a theme's card surfaces and controls are made of. */
enum class HaSurfaceMaterial {
  /** Flat colour: every theme but the skeuomorphic one. */
  Flat,
  /**
   * Painted metal: a card is a lit panel — a sheen falling from the top edge into shade — and a
   * switch knob is a machined metal cap in a recessed slot.
   */
  PaintedMetal,
}

/**
 * One uber theme.
 *
 * @property light the palette in light mode, or null for a dark-only theme.
 * @property dark the palette in dark mode, or null for a light-only theme.
 * @property visuals the family of meter designs the theme draws with.
 * @property readout how a measurement sets its value text.
 * @property ink one colour every reading, icon and control is drawn in, replacing the semantic data
 *   colours (rule 2 of the theming rules). Only for themes that are monochrome by nature — the
 *   reading's state is still carried by fill level and segment count.
 * @property hotInk the second ink an inked theme lights its hot / low segments in, like the red
 *   overlay segments of a real LCD.
 * @property fontFamily the typeface every card text uses.
 * @property letterSpacingSp extra tracking, in sp, for every card text.
 * @property valueWeight the weight of readings (`HaType.Value*`).
 */
data class HaUberTheme(
  val id: String,
  val displayName: String,
  val light: HaTheme?,
  val dark: HaTheme?,
  val shape: HaCardShapeStyle = HaCardShapeStyle(),
  val visuals: HaVisualFamily = HaVisualFamily.Classic,
  val readout: HaReadoutStyle = HaReadoutStyle.Plain,
  val ink: Color? = null,
  val darkInk: Color? = ink,
  val hotInk: Color? = null,
  val darkHotInk: Color? = hotInk,
  val fontFamily: RemoteFontFamily = RemoteFontFamily.Default,
  val letterSpacingSp: Float = 0f,
  val valueWeight: FontWeight = FontWeight.SemiBold,
) {
  init {
    require(light != null || dark != null) { "$id needs a light or a dark palette" }
  }

  /** The palette for [darkTheme], falling back to the one the theme has. */
  fun palette(darkTheme: Boolean): HaTheme = (if (darkTheme) dark ?: light else light ?: dark)!!

  /** The ink for [darkTheme], or null to keep the semantic data colours. */
  fun inkFor(darkTheme: Boolean): Color? = if (darkTheme) darkInk else ink

  /** The hot ink for [darkTheme], or null for the semantic critical colour. */
  fun hotInkFor(darkTheme: Boolean): Color? = if (darkTheme) darkHotInk else hotInk

  companion object {
    /** The cards' own look: HA's palette, 12dp cards, smooth meters. The default. */
    val Classic =
      HaUberTheme(
        id = "classic",
        displayName = "Classic",
        light = HaTheme.Light,
        dark = HaTheme.Dark,
      )

    /**
     * **Material 3 Expressive.** Big 28dp corners with no border, the Expressive tonal palette,
     * chunky round strokes with the track gap and stop dot of M3E's progress indicators, and bold
     * numerals.
     */
    val Expressive =
      HaUberTheme(
        id = "expressive",
        displayName = "Material 3 Expressive",
        light = expressivePalette(dark = false),
        dark = expressivePalette(dark = true),
        shape = HaCardShapeStyle(cornerDp = 28, borderDp = 0, controlCornerDp = 16),
        visuals = HaVisualFamily.Expressive,
        readout = HaReadoutStyle.TonalPill,
        valueWeight = FontWeight.Bold,
      )

    /**
     * **Trinitron** — the techy Sony look: graphite and hairlines, monospace with wide tracking,
     * and meters as fine tick scales like a hi-fi deck's level meter. Dark only.
     */
    val Trinitron =
      HaUberTheme(
        id = "trinitron",
        displayName = "Trinitron",
        light = null,
        dark =
          HaTheme(
            cardBackground = Color(0xFF17181B),
            dashboardBackground = Color(0xFF0B0C0E),
            sectionBackground = Color(0xFF0B0C0E),
            primaryText = Color(0xFFEDEEF0),
            secondaryText = Color(0xFF8A8F98),
            linkText = Color(0xFF4FC3F7),
            divider = Color(0xFF2E3137),
            placeholderAccent = Color(0xFF4FC3F7),
            placeholderBackground = Color(0xFF1E2126),
            unknownAccent = Color(0xFF8A8F98),
            isDark = true,
          ),
        shape =
          HaCardShapeStyle(cornerDp = 2, borderDp = 1, controlCornerDp = 2, squareKnob = true),
        visuals = HaVisualFamily.Trinitron,
        readout = HaReadoutStyle.Plate,
        fontFamily = RemoteFontFamily.Monospace,
        letterSpacingSp = 1.2f,
        valueWeight = FontWeight.Normal,
      )

    /**
     * **LCD** — a reflective segment display: green-grey glass and dark ink, chunky segments with
     * their unlit ghosts showing. Dark mode is the Indiglo backlight: cyan ink on a deep blue
     * panel.
     */
    val Lcd =
      HaUberTheme(
        id = "lcd",
        displayName = "LCD",
        light =
          HaTheme(
            cardBackground = Color(0xFFB9C3A2),
            dashboardBackground = Color(0xFF8E9878),
            sectionBackground = Color(0xFF8E9878),
            primaryText = Color(0xFF1C2416),
            secondaryText = Color(0xFF46503A),
            linkText = Color(0xFF1C2416),
            divider = Color(0xFF9EA889),
            placeholderAccent = Color(0xFF46503A),
            placeholderBackground = Color(0xFFA9B391),
            unknownAccent = Color(0xFF46503A),
            isDark = false,
          ),
        dark =
          HaTheme(
            cardBackground = Color(0xFF07263A),
            dashboardBackground = Color(0xFF02121C),
            sectionBackground = Color(0xFF02121C),
            primaryText = Color(0xFF8AF4FF),
            secondaryText = Color(0xFF3FA9B8),
            linkText = Color(0xFF8AF4FF),
            divider = Color(0xFF0E3A52),
            placeholderAccent = Color(0xFF3FA9B8),
            placeholderBackground = Color(0xFF0B3147),
            unknownAccent = Color(0xFF3FA9B8),
            isDark = true,
          ),
        shape =
          HaCardShapeStyle(cornerDp = 4, borderDp = 2, controlCornerDp = 0, squareKnob = true),
        visuals = HaVisualFamily.Lcd,
        readout = HaReadoutStyle.Screen,
        ink = Color(0xFF1C2416),
        darkInk = Color(0xFF8AF4FF),
        hotInk = Color(0xFFB3261E),
        darkHotInk = Color(0xFFFF6B6B),
        fontFamily = RemoteFontFamily.Monospace,
        valueWeight = FontWeight.Bold,
      )

    /**
     * **Braun** — Dieter Rams, 1962: warm off-white (or Braun black), soft corners, no border,
     * hairline strokes; the semantic colours stay, because Rams let colour mean something.
     */
    val Braun =
      HaUberTheme(
        id = "braun",
        displayName = "Braun",
        light =
          HaTheme(
            cardBackground = Color(0xFFF8F6F0),
            dashboardBackground = Color(0xFFE9E5DB),
            sectionBackground = Color(0xFFE9E5DB),
            primaryText = Color(0xFF262624),
            secondaryText = Color(0xFF7A766E),
            linkText = Color(0xFFD9541E),
            divider = Color(0xFFDAD5C9),
            placeholderAccent = Color(0xFFD9541E),
            placeholderBackground = Color(0xFFEFEBE2),
            unknownAccent = Color(0xFF7A766E),
            isDark = false,
          ),
        dark =
          HaTheme(
            cardBackground = Color(0xFF2B2A27),
            dashboardBackground = Color(0xFF1B1A18),
            sectionBackground = Color(0xFF1B1A18),
            primaryText = Color(0xFFEFECE4),
            secondaryText = Color(0xFF9A968D),
            linkText = Color(0xFFF07A3C),
            divider = Color(0xFF45433E),
            placeholderAccent = Color(0xFFF07A3C),
            placeholderBackground = Color(0xFF34322E),
            unknownAccent = Color(0xFF9A968D),
            isDark = true,
          ),
        shape = HaCardShapeStyle(cornerDp = 18, borderDp = 0),
        visuals = HaVisualFamily.Braun,
        fontFamily = RemoteFontFamily.SansSerif,
        valueWeight = FontWeight.Medium,
      )

    /**
     * **Nixie** — black glass and glowing orange: every reading is a lit filament with a halo,
     * numerals light-weight like a tube's wire digits. Dark only.
     */
    val Nixie =
      HaUberTheme(
        id = "nixie",
        displayName = "Nixie",
        light = null,
        dark =
          HaTheme(
            cardBackground = Color(0xFF15100C),
            dashboardBackground = Color(0xFF080604),
            sectionBackground = Color(0xFF080604),
            primaryText = Color(0xFFFFA24D),
            secondaryText = Color(0xFF9C5A2A),
            linkText = Color(0xFFFFA24D),
            divider = Color(0xFF2D2018),
            placeholderAccent = Color(0xFF9C5A2A),
            placeholderBackground = Color(0xFF221810),
            unknownAccent = Color(0xFF9C5A2A),
            isDark = true,
          ),
        shape = HaCardShapeStyle(cornerDp = 14, borderDp = 1, controlCornerDp = 4),
        visuals = HaVisualFamily.Nixie,
        ink = Color(0xFFFF8A2A),
        fontFamily = RemoteFontFamily.SansSerif,
        letterSpacingSp = 0.4f,
        valueWeight = FontWeight.Light,
      )

    /**
     * **Console** — a skeuomorphic 1970s broadcast desk: a hammertone blue-grey panel lit from
     * above, cream silkscreen, a backlit VU meter behind glass, metal fader caps and dotted lamp
     * rings. An original drawing of the genre — the VU scale is the ANSI C16.5 standard one — not a
     * copy of any product's artwork. Dark only.
     */
    val Console =
      HaUberTheme(
        id = "console",
        displayName = "Console",
        light = null,
        dark =
          HaTheme(
            cardBackground = Color(0xFF2F4A57),
            dashboardBackground = Color(0xFF1A2A32),
            sectionBackground = Color(0xFF1A2A32),
            primaryText = Color(0xFFF1ECDD),
            secondaryText = Color(0xFFB3C2C8),
            linkText = Color(0xFFF2A93B),
            divider = Color(0xFF1C2D35),
            placeholderAccent = Color(0xFFF2A93B),
            placeholderBackground = Color(0xFF26404C),
            unknownAccent = Color(0xFFB3C2C8),
            isDark = true,
          ),
        shape =
          HaCardShapeStyle(
            cornerDp = 6,
            borderDp = 1,
            controlCornerDp = 3,
            squareKnob = true,
            material = HaSurfaceMaterial.PaintedMetal,
          ),
        visuals = HaVisualFamily.Console,
        readout = HaReadoutStyle.Plate,
        fontFamily = RemoteFontFamily.SansSerif,
        letterSpacingSp = 0.6f,
        valueWeight = FontWeight.Medium,
      )

    /** Every uber theme, [Classic] first. */
    val all: List<HaUberTheme> = listOf(Classic, Expressive, Trinitron, Lcd, Braun, Nixie, Console)

    /** The theme with [id], or [Classic]. */
    fun byId(id: String): HaUberTheme =
      all.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) } ?: Classic
  }
}

/** The Expressive tonal palette: M3's baseline purple through materialkolor's Expressive style. */
private fun expressivePalette(dark: Boolean): HaTheme {
  val m3 =
    dynamicColorScheme(
      seedColor = Color(0xFF6750A4),
      isDark = dark,
      isAmoled = false,
      style = PaletteStyle.Expressive,
    )
  return HaTheme(
    cardBackground = m3.surfaceContainerHigh,
    dashboardBackground = m3.surface,
    sectionBackground = m3.surfaceContainer,
    primaryText = m3.onSurface,
    secondaryText = m3.onSurfaceVariant,
    linkText = m3.primary,
    // M3 Expressive draws indicator tracks in secondaryContainer; the cards are borderless, so the
    // divider is free to be that track tone.
    divider = m3.secondaryContainer,
    placeholderAccent = m3.primary,
    placeholderBackground = m3.primaryContainer,
    unknownAccent = m3.tertiary,
    isDark = dark,
  )
}

/**
 * The active uber theme and mode. [ownPalette] is false under [ProvideHaUberStyle], where the cards
 * sit on the host's surfaces rather than [theme]'s.
 */
data class HaActiveUberTheme(
  val theme: HaUberTheme,
  val darkTheme: Boolean,
  val ownPalette: Boolean = true,
) {
  /** The ink readings are drawn in, or null for the semantic colours. */
  val ink: Color?
    get() = theme.inkFor(darkTheme)

  /** The hot ink, or null for the semantic critical colour. */
  val hotInk: Color?
    get() = theme.hotInkFor(darkTheme)
}

val LocalHaUberTheme = staticCompositionLocalOf {
  HaActiveUberTheme(HaUberTheme.Classic, darkTheme = false)
}

/**
 * Provide [theme] to every card part in [content]: its palette (through [ProvideHaTheme]), shape,
 * meter style, ink and typeface.
 */
@Composable
fun ProvideHaUberTheme(theme: HaUberTheme, darkTheme: Boolean, content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalHaUberTheme provides HaActiveUberTheme(theme, darkTheme)) {
    ProvideHaTheme(theme.palette(darkTheme), content)
  }
}

/** The active uber theme. */
@Composable
@ReadOnlyComposable
fun currentHaUberTheme(): HaActiveUberTheme = LocalHaUberTheme.current

/**
 * The text style every card text uses: the uber theme's typeface and tracking over Remote Compose's
 * default. Pass as `style =` wherever a card used `RemoteTextStyle.Default`.
 */
@Composable
@ReadOnlyComposable
fun haTextStyle(): RemoteTextStyle {
  val theme = LocalHaUberTheme.current.theme
  if (theme.fontFamily == RemoteFontFamily.Default && theme.letterSpacingSp == 0f) {
    return RemoteTextStyle.Default
  }
  return RemoteTextStyle.Default.merge(
    RemoteTextStyle(fontFamily = theme.fontFamily, letterSpacing = theme.letterSpacingSp.rsp)
  )
}

/** The weight readings are set in under the active uber theme. */
@Composable
@ReadOnlyComposable
fun haValueWeight(): FontWeight = LocalHaUberTheme.current.theme.valueWeight

/**
 * This accent under the active uber theme: unchanged for themes that keep semantic colours, and the
 * theme's ink (on) and its faded ink (off) for a monochrome one — so an LCD's icons and switches
 * are printed in the same ink as its segments.
 */
@Composable
@ReadOnlyComposable
fun HaToggleAccent.themed(): HaToggleAccent {
  val ink = LocalHaUberTheme.current.ink ?: return this
  return copy(activeAccent = ink.rc, inactiveAccent = ink.copy(alpha = 0.35f).rc)
}

/**
 * The card palette under this uber theme for an app on palette [style]: [HaUberTheme.Classic]
 * follows the palette (`haThemeFor`), every other uber theme brings its own.
 */
fun HaUberTheme.cardPalette(style: ThemeStyle, darkTheme: Boolean): HaTheme =
  if (id == HaUberTheme.Classic.id) haThemeFor(style, darkTheme) else palette(darkTheme)

/**
 * Provide [theme] for cards with [palette] (from [cardPalette], usually remembered by the caller):
 * the host-side entry point for rendering a dashboard card in the user's card style.
 */
@Composable
fun ProvideCardStyle(
  theme: HaUberTheme,
  palette: HaTheme,
  darkTheme: Boolean,
  content: @Composable () -> Unit,
) {
  ProvideHaUberTheme(theme.copy(light = palette, dark = palette), darkTheme, content)
}

/**
 * [color] as the active uber theme prints it: the theme's ink for a monochrome theme (LCD, Nixie),
 * else [color] unchanged — for an accent that marks something (a calendar dot, a keypad ring, a
 * media control) rather than a semantic band a meter reads.
 */
@Composable
@ReadOnlyComposable
fun haInk(color: RemoteColor): RemoteColor = LocalHaUberTheme.current.ink?.rc ?: color

/**
 * A marking accent known at capture, as the active uber theme prints it: the theme's ink for a
 * monochrome theme, else [color] made legible on the theme's card surface ([haLegible]).
 */
@Composable
@ReadOnlyComposable
fun haInk(color: Color): RemoteColor {
  val active = LocalHaUberTheme.current
  return (active.ink ?: legibleOn(active, color)).rc
}

/**
 * [color] legible on the active card surface: unchanged under Classic (the cards' own look), under
 * [ProvideHaUberStyle] (the host's surfaces, which the accents already suit), and whenever it
 * already contrasts 3:1 with the card background — the WCAG minimum for graphics and large text.
 * Otherwise it is mixed toward the theme's primary text, a tenth at a time, until it does: a green
 * "Arm away" or a purple media accent on Console's blue panel lightens rather than sinking into it,
 * and keeps its hue.
 */
@Composable
@ReadOnlyComposable
fun haLegible(color: Color): Color = legibleOn(LocalHaUberTheme.current, color)

internal fun legibleOn(active: HaActiveUberTheme, color: Color): Color {
  if (active.theme.id == HaUberTheme.Classic.id || !active.ownPalette) return color
  val palette = active.theme.palette(active.darkTheme)
  val surface = palette.cardBackground
  var mixed = color
  var t = 0f
  while (contrast(mixed, surface) < MinAccentContrast && t < 1f) {
    t += 0.1f
    mixed = lerp(color, palette.primaryText, t)
  }
  return mixed
}

/** The WCAG contrast ratio between [a] and [b]. */
internal fun contrast(a: Color, b: Color): Float {
  val la = a.luminance()
  val lb = b.luminance()
  return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

private const val MinAccentContrast = 3f

/**
 * The shape of a control inside a card (a key, button, chip or badge) under the active uber theme.
 * Classic keeps its own: [classicDp] corners, or a circle when [round]. A square-cornered theme
 * (Trinitron, LCD, Console — `squareKnob`) squares every control off at its control corner; the
 * soft ones keep circles round and round their corners at least to their control corner
 * (Expressive's 16dp).
 */
@Composable
@ReadOnlyComposable
fun haControlShape(classicDp: Int, round: Boolean = false): RemoteShape {
  val theme = LocalHaUberTheme.current.theme
  val shape = theme.shape
  return when {
    theme.id == HaUberTheme.Classic.id ->
      if (round) RemoteCircleShape else RemoteRoundedCornerShape(classicDp.rdp)
    shape.squareKnob -> RemoteRoundedCornerShape(shape.controlCornerDp.rdp)
    round -> RemoteCircleShape
    else -> RemoteRoundedCornerShape(maxOf(classicDp, shape.controlCornerDp).rdp)
  }
}

/**
 * Provide [theme]'s design to every card part in [content] — meter family, readout, shapes,
 * typeface and ink — while keeping the palette the host already provides. For a host with colours
 * of its own that the user picked separately, such as a Wear watch face's Material You scheme.
 */
@Composable
fun ProvideHaUberStyle(theme: HaUberTheme, darkTheme: Boolean, content: @Composable () -> Unit) {
  CompositionLocalProvider(
    LocalHaUberTheme provides HaActiveUberTheme(theme, darkTheme, ownPalette = false),
    content = content,
  )
}
