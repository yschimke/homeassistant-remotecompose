package ee.schimke.ha.rc

import ee.schimke.composeai.rcplayer.compose.RcBundledTypefaceLoader
import ee.schimke.composeai.rcplayer.compose.RcFontFace
import ee.schimke.composeai.rcplayer.compose.RcFontFaces
import ee.schimke.composeai.rcplayer.compose.RcTypefaceLoader
import ee.schimke.ha.rc.components.HaBundledFonts
import ee.schimke.ha.rc.components.HaDigital7Font
import ee.schimke.ha.rc.components.HaGSpotFont

/**
 * The app's bundled fonts ([HaBundledFonts]) as a Compose player's typeface loader, so a card that
 * names one (`RemoteFontFamily.Named`) plays it on Android, desktop and the browser alike.
 */
fun haTypefaceLoader(): RcTypefaceLoader =
  RcBundledTypefaceLoader(
    mapOf(
      HaDigital7Font to
        RcFontFaces(
          RcFontFace(HaDigital7Font, HaBundledFonts.digital7, weight = 400, italic = true),
        ),
      // One weight: Russo One is drawn heavy, so every heading weight takes it.
      HaGSpotFont to
        RcFontFaces(RcFontFace(HaGSpotFont, HaBundledFonts.russoOne, weight = 400, italic = false)),
    ),
  )

/** One loader per process: the faces are parsed once. */
val HaTypefaceLoader: RcTypefaceLoader by lazy { haTypefaceLoader() }
