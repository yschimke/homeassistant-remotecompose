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
  val size = LocalWidgetCaptureSizeDp.current ?: return false
  if (
    LocalCardSizeMode.current != CardSizeMode.Fixed ||
      LocalFixedCanvasAxis.current != BreakpointAxis.Width ||
      size.widthDp >= 120
  )
    return false
  val first = card.raw["entities"]?.jsonArray?.firstOrNull() ?: return false
  val row =
    when (first) {
      is JsonPrimitive -> mapOf("entity" to first)
      is JsonObject -> first
      else -> return false
    }
  TileCardConverter()
    .Render(
      CardConfig("tile", JsonObject(row + ("type" to JsonPrimitive("tile")))),
      snapshot,
      modifier,
    )
  return true
}
