@file:Suppress("RestrictedApi")

package ee.schimke.terrazzo.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CachedCardPreview
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.ProvideCardSizeMode
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.androidXExperimentalWrap
import ee.schimke.ha.rc.cardDataSignature
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ProvideCardStyle
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.cardPalette
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The kiosk's headline row as real Home Assistant cards — a light tile, a thermostat and a humidity
 * gauge — converted and played exactly as the phone dashboard plays them, in [cardStyle] over
 * [style]'s palette: so a styled kiosk shows the style's own dials and meters, not a mock-up. Cells
 * are a fixed size, so each card takes its compact (Fixed-mode) layout.
 */
@Composable
fun TvKioskCards(
  cardStyle: HaUberTheme,
  style: ThemeStyle,
  snapshot: HaSnapshot,
  modifier: Modifier = Modifier,
) {
  val registry = remember { defaultRegistry() }
  val palette = remember(cardStyle, style) { cardStyle.cardPalette(style, darkTheme = true) }
  Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    KioskCards.forEach { card ->
      // Re-encode cards (the thermostat) refresh by their data signature; binding cards push
      // their new values into the running player.
      val signature = registry.cardDataSignature(card, snapshot)
      CachedCardPreview(
        cacheKey = KioskCardKey(card, style, cardStyle.id, signature),
        profile = androidXExperimentalWrap,
        modifier = Modifier.width(220.dp).height(132.dp),
        card = card,
        snapshot = snapshot,
        liveBindings = signature == null,
      ) {
        ProvideCardRegistry(registry) {
          ProvideCardStyle(cardStyle, palette, darkTheme = true) {
            ProvideCardSizeMode(CardSizeMode.Fixed) {
              RenderChild(card, snapshot, RemoteModifier.fillMaxSize())
            }
          }
        }
      }
    }
  }
}

private data class KioskCardKey(
  val card: CardConfig,
  val style: ThemeStyle,
  val cardStyle: String,
  val signature: String?,
)

private fun card(json: String): CardConfig {
  val raw = Json.parseToJsonElement(json).jsonObject
  return CardConfig(type = raw["type"]!!.toString().trim('"'), raw = raw)
}

private val KioskCards =
  listOf(
    card("""{"type":"tile","entity":"${TvDemoData.LIGHT}"}"""),
    card("""{"type":"thermostat","entity":"${TvDemoData.CLIMATE}"}"""),
    card(
      """{"type":"gauge","entity":"${TvDemoData.HUMIDITY}","name":"Humidity","min":0,"max":100}"""
    ),
  )
