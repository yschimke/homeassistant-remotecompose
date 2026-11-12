package ee.schimke.ha.rc.components

import androidx.compose.ui.text.font.FontWeight
import ee.schimke.composeai.rccreation.compose.text.RemoteFontWeight

/**
 * This weight for Compose UI text: a theme's weights are [RemoteFontWeight]s, which a card's text
 * takes, and a screen drawn beside the cards (a style picker's sample, the TV kiosk's band) reads
 * the same ones. A theme's weights are constants; one that isn't reads as normal.
 */
fun RemoteFontWeight.toFontWeight(): FontWeight =
  FontWeight((weight.constantValueOrNull ?: 400f).toInt().coerceIn(1, 1000))
