package ee.schimke.terrazzo.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import ee.schimke.terrazzo.R

/**
 * Google Fonts downloadable provider. Off-device fonts arrive via Google
 * Play Services — no APK-size cost, and the Compose font loader falls
 * back to a system face of the same weight while a download is in
 * flight. If we later need deterministic Robolectric previews we'll add
 * a `debug` source set that bundles the same families as TTFs, the way
 * Confetti's Wear app does.
 */
private val googleFontsProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private fun family(name: String): FontFamily {
    val gf = GoogleFont(name)
    return FontFamily(
        Font(googleFont = gf, fontProvider = googleFontsProvider, weight = FontWeight.Normal, style = FontStyle.Normal),
        Font(googleFont = gf, fontProvider = googleFontsProvider, weight = FontWeight.Medium, style = FontStyle.Normal),
        Font(googleFont = gf, fontProvider = googleFontsProvider, weight = FontWeight.SemiBold, style = FontStyle.Normal),
        Font(googleFont = gf, fontProvider = googleFontsProvider, weight = FontWeight.Bold, style = FontStyle.Normal),
    )
}

internal val RobotoFlexFamily: FontFamily = family("Roboto Flex")
internal val InterFamily: FontFamily = family("Inter")
internal val AtkinsonHyperlegibleFamily: FontFamily = family("Atkinson Hyperlegible")
internal val LexendFamily: FontFamily = family("Lexend")
