package ee.schimke.ha.rc.components

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont

/**
 * Font sources for the Terrazzo palettes. All fonts are loaded via the
 * downloadable-fonts provider (`com.google.android.gms`) so the APK
 * doesn't ship any TTFs — see `res/values/font_certs.xml` for the
 * provider certificate fingerprints.
 *
 * Every `FontFamily` here includes four weights (400/500/600/700). The
 * downloader fetches what the screen actually asks for; an unused
 * weight never reaches the device.
 */
internal val TerrazzoFontProvider: GoogleFont.Provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private fun googleFamily(name: String): FontFamily = FontFamily(
    Font(GoogleFont(name), TerrazzoFontProvider, FontWeight.Normal),
    Font(GoogleFont(name), TerrazzoFontProvider, FontWeight.Medium),
    Font(GoogleFont(name), TerrazzoFontProvider, FontWeight.SemiBold),
    Font(GoogleFont(name), TerrazzoFontProvider, FontWeight.Bold),
)

/**
 * **Roboto Flex** — variable-axis companion to Roboto. Drives display
 * and title roles for the Home palette. The weight axis responds to
 * optical size, which matters for the HA tile card's large state
 * numerals.
 */
val RobotoFlexFamily: FontFamily = googleFamily("Roboto Flex")

/**
 * **Inter** — hinted for UI at 12-14 dp, where speaker names, entity
 * times, and secondary labels live. Used as the body face for Home and
 * pairs cleanly with both Roboto Flex and Atkinson Hyperlegible.
 */
val InterFamily: FontFamily = googleFamily("Inter")

/**
 * **Figtree** — friendly geometric sans by Erik D. Kennedy. Carries the
 * Mushroom palette's warm, rounded, organic feel. Single family for both
 * display and body — Figtree's weight range (300-900) covers the whole
 * M3 scale.
 */
val FigtreeFamily: FontFamily = googleFamily("Figtree")

/**
 * **IBM Plex Sans** — neutral, engineered, unmistakably utilitarian.
 * The Minimalist palette's signature. Numeral tabular figures are
 * reliable on it (important for the cluster of state values on a
 * data-dense dashboard).
 */
val IbmPlexSansFamily: FontFamily = googleFamily("IBM Plex Sans")

/**
 * **Atkinson Hyperlegible** — designed by the Braille Institute for
 * maximum character distinctiveness at distance and low-vision contexts.
 * Every glyph is shaped to be un-confusable with every other. The only
 * reasonable choice for a wall-mounted kiosk or a TV dashboard viewed
 * from the sofa.
 */
val AtkinsonHyperlegibleFamily: FontFamily = googleFamily("Atkinson Hyperlegible")
