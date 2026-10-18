@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.text.RemoteFontFamily
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/**
 * Original adaptations of community interface idioms; provenance in
 * docs/design/community-families.md.
 */
internal object CommunityThemes {
  val Mushroom =
    family(
      "mushroom",
      "Mushroom",
      HaVisualFamily.Mushroom,
      "Friendly garden · petal scales and soft seed controls",
      palette(false, 0xFFFFFEFA, 0xFFF0F2E9, 0xFF202B24, 0xFF526157, 0xFF356541, 0xFFD5DFD1),
      palette(true, 0xFF242D27, 0xFF151D18, 0xFFF0F4E8, 0xFFB6C6B7, 0xFFA5D6A7, 0xFF415044),
      HaCardShapeStyle(20, 0, 11),
      HaReadoutStyle.TonalPill,
      FontWeight.SemiBold,
    )
  val Bubble =
    family(
      "bubble",
      "Bubble",
      HaVisualFamily.Bubble,
      "Floating capsules · bead meters and round satellite controls",
      palette(false, 0xFFF8F5FF, 0xFFEAE5F4, 0xFF292335, 0xFF62576F, 0xFF705090, 0xFFDBD0E9),
      palette(true, 0xFF332B43, 0xFF1B1725, 0xFFF7EFFC, 0xFFC8B9D8, 0xFFD1B3F3, 0xFF574465),
      HaCardShapeStyle(32, 0, 16),
      HaReadoutStyle.TonalPill,
      FontWeight.Bold,
    )
  val Minimalist =
    family(
      "minimalist",
      "UI Minimalist",
      HaVisualFamily.Minimalist,
      "Compact modules · discrete pips and offset index tabs",
      palette(false, 0xFFFFFFFF, 0xFFF0F2F6, 0xFF222937, 0xFF566176, 0xFF315AA4, 0xFFD8DFEB),
      palette(true, 0xFF252A36, 0xFF151923, 0xFFF3F5FA, 0xFFBCC6D8, 0xFFA8C8FF, 0xFF454F64),
      HaCardShapeStyle(16, 0, 7, true),
      HaReadoutStyle.Plain,
      FontWeight.Bold,
    )
  val Cupertino =
    family(
      "cupertino",
      "Control Centre",
      HaVisualFamily.Cupertino,
      "Clear modules · concentric activity rings and filled sliders",
      palette(false, 0xFFF8FAFF, 0xFFDDE5F2, 0xFF17263A, 0xFF4F6178, 0xFF145BBD, 0xFFC7D4E5),
      palette(true, 0xFF293443, 0xFF101925, 0xFFF5F8FF, 0xFFC1CDDF, 0xFF91C2FF, 0xFF52647B),
      HaCardShapeStyle(26, 1, 14),
      HaReadoutStyle.Plain,
      FontWeight.Medium,
    )
  val Catppuccin =
    family(
      "catppuccin",
      "Catppuccin",
      HaVisualFamily.Catppuccin,
      "Pastel workspace · stitched lozenges and soft tab controls",
      palette(false, 0xFFEFF1F5, 0xFFDCE0E8, 0xFF4C4F69, 0xFF5C5F77, 0xFF8839EF, 0xFFBCC0CC),
      palette(true, 0xFF1E1E2E, 0xFF11111B, 0xFFCDD6F4, 0xFFBAC2DE, 0xFFCBA6F7, 0xFF45475A),
      HaCardShapeStyle(14, 1, 8),
      HaReadoutStyle.Screen,
      FontWeight.Medium,
    )
  val Obsidian =
    family(
      "obsidian",
      "Obsidian Minimal",
      HaVisualFamily.Obsidian,
      "Quiet notebook · ruled scales and editorial readings",
      palette(false, 0xFFFFFDF7, 0xFFF0EDE5, 0xFF292822, 0xFF625F54, 0xFF725690, 0xFFD7D2C7),
      palette(true, 0xFF242320, 0xFF171614, 0xFFEDEAE3, 0xFFBBB5A8, 0xFFD6B5ED, 0xFF4E4A43),
      HaCardShapeStyle(5, 1, 3, true),
      HaReadoutStyle.Plate,
      FontWeight.Normal,
      RemoteFontFamily.Serif,
    )
  val Noctis =
    family(
      "noctis",
      "Noctis",
      HaVisualFamily.Noctis,
      "Night navigation · orbital scales and twin rails",
      palette(false, 0xFFF0F6FC, 0xFFDCE8F4, 0xFF152D47, 0xFF455F79, 0xFF195994, 0xFFBECDDE),
      palette(true, 0xFF192D43, 0xFF0A1726, 0xFFE4EFFA, 0xFFA9C1D9, 0xFF76BBF3, 0xFF365572),
      HaCardShapeStyle(12, 1, 5),
      HaReadoutStyle.Plate,
      FontWeight.Light,
    )
  val Lcars =
    family(
      "lcars",
      "LCARS · Starship",
      HaVisualFamily.Lcars,
      "Starship panels · elbow bands and asymmetric command keys",
      palette(false, 0xFFF9F4EC, 0xFFE7DDCF, 0xFF27202C, 0xFF655368, 0xFF875017, 0xFFD0BBDC),
      palette(true, 0xFF161321, 0xFF07060C, 0xFFF6DFC0, 0xFFD3B5E6, 0xFFFFBE78, 0xFF594568),
      HaCardShapeStyle(24, 2, 10, true),
      HaReadoutStyle.Plate,
      FontWeight.Bold,
      RemoteFontFamily.SansSerif,
      0.6f,
    )
  val Arctic =
    family(
      "arctic",
      "Arctic",
      HaVisualFamily.Arctic,
      "Open horizon · suspended needles and clear ice rails",
      palette(false, 0xFFF8FCFD, 0xFFE1EEF1, 0xFF1A353E, 0xFF4A6570, 0xFF206479, 0xFFC0D5DD),
      palette(true, 0xFF192F39, 0xFF0C1E27, 0xFFEEF8FA, 0xFFB0CBD5, 0xFF8BD8EA, 0xFF3A5966),
      HaCardShapeStyle(3, 0, 2, true),
      HaReadoutStyle.Plain,
      FontWeight.Light,
      RemoteFontFamily.SansSerif,
      0.4f,
    )
  val Aeon =
    family(
      "aeon",
      "Aeon",
      HaVisualFamily.Aeon,
      "Cinema marquee · diamond indexes and framed command keys",
      palette(false, 0xFFFFFCF5, 0xFFE9E2D6, 0xFF30281D, 0xFF685C49, 0xFF795623, 0xFFD3C4A9),
      palette(true, 0xFF28231E, 0xFF100E0C, 0xFFF7ECD6, 0xFFCDBE9D, 0xFFE4BE71, 0xFF5B4B32),
      HaCardShapeStyle(1, 2, 1, true),
      HaReadoutStyle.Screen,
      FontWeight.Medium,
      RemoteFontFamily.Serif,
      0.3f,
    )

  private fun family(
    id: String,
    name: String,
    visuals: HaVisualFamily,
    description: String,
    light: HaTheme,
    dark: HaTheme,
    shape: HaCardShapeStyle,
    readout: HaReadoutStyle,
    weight: FontWeight,
    font: RemoteFontFamily = RemoteFontFamily.SansSerif,
    tracking: Float = 0f,
  ) =
    HaUberTheme(
      id,
      name,
      light,
      dark,
      shape = shape,
      visuals = visuals,
      readout = readout,
      fontFamily = font,
      valueWeight = weight,
      letterSpacingSp = tracking,
      description = description,
    )

  private fun palette(
    dark: Boolean,
    card: Long,
    background: Long,
    text: Long,
    secondary: Long,
    accent: Long,
    track: Long,
  ) =
    HaTheme(
      cardBackground = Color(card),
      dashboardBackground = Color(background),
      sectionBackground = Color(background),
      primaryText = Color(text),
      secondaryText = Color(secondary),
      linkText = Color(accent),
      divider = Color(track),
      placeholderAccent = Color(accent),
      placeholderBackground = Color(track),
      unknownAccent = Color(secondary),
      isDark = dark,
    )
}
