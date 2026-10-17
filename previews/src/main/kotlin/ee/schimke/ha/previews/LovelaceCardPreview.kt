@file:Suppress("RestrictedApi", "COMPOSE_APPLIER_CALL_MISMATCH")

package ee.schimke.ha.previews

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.overrides.previewOverrideString
import ee.schimke.composeai.preview.CatalogComponent
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ProvideCardStyle
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.cardPalette
import kotlinx.serialization.json.Json

/** Live render entry point for the dashboard host: the app's actual converters and card styles. */
@CatalogComponent(
  id = "Dashboard/CardEditor",
  group = "Dashboard",
  noReference = "Host-supplied Lovelace config and snapshot, rendered by harc's converters.",
)
@Preview(widthDp = 360, heightDp = 320)
@Composable
fun LovelaceCardPreview() {
  val cardJson = previewOverrideString("cardJson", "{\"type\":\"tile\",\"entity\":\"light.s0\"}")
  val snapshotJson = previewOverrideString("snapshotJson", "{}")
  val cardStyle = previewOverrideString("cardStyle", "classic")
  val dark = previewOverrideString("dark", "false").toBooleanStrict()
  val card = Json.decodeFromString(CardConfig.serializer(), cardJson)
  val snapshot = Json.decodeFromString(HaSnapshot.serializer(), snapshotJson)
  val style = HaUberTheme.byId(cardStyle)
  val palette = style.cardPalette(ThemeStyle.TerrazzoHome, dark)
  CardHost(palette) {
    ProvideCardStyle(style, palette, dark) {
      RenderChild(card, snapshot, RemoteModifier.fillMaxWidth())
    }
  }
}
