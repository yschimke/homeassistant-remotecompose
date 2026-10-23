package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.BreakpointAxis
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.LocalFixedCanvasAxis
import ee.schimke.ha.rc.LocalWidgetCaptureSizeDp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

/**
 * One-cell launcher lists show their first entity as an icon/state tile instead of clipping a full
 * list. Its standard named bindings, icon and tap action survive design-template baking. Launcher
 * resize recaptures at the new size; Wear and dashboard layouts keep their own tiers.
 */
@Composable
internal fun CompactLauncherList(
  card: CardConfig,
  snapshot: HaSnapshot,
  modifier: RemoteModifier,
): Boolean {
  val tile = compactLauncherTile(card) ?: return false
  TileCardConverter().Render(tile, snapshot, modifier)
  return true
}

/**
 * The tile a one-cell launcher list shows its first entity as ([CompactLauncherList]), or null when
 * the canvas isn't a launcher cell that narrow or the card has no entity.
 */
@Composable
internal fun compactLauncherTile(card: CardConfig): CardConfig? {
  val size = LocalWidgetCaptureSizeDp.current ?: return null
  if (
    LocalCardSizeMode.current != CardSizeMode.Fixed ||
      LocalFixedCanvasAxis.current != BreakpointAxis.Width ||
      size.widthDp >= 120
  )
    return null
  val first = card.raw["entities"]?.jsonArray?.firstOrNull() ?: return null
  val row =
    when (first) {
      is JsonPrimitive -> mapOf("entity" to first)
      is JsonObject -> first
      else -> return null
    }
  return CardConfig("tile", JsonObject(row + ("type" to JsonPrimitive("tile"))))
}
