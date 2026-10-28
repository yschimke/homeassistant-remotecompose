@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards.shutter

import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable

/**
 * `custom:enhanced-shutter-card` card data: a list of cover rows. Each row draws a window shutter
 * (or a garage door, for a cover whose `device_class` is `garage` or `gate`), its up / stop / down
 * buttons and a state line.
 *
 * Every value a row shows follows its cover's bindings ([CoverLive]): the card is captured once and
 * never re-encodes for a state change (live-card-updates.md, path 1).
 */
data class HaShutterCardData(val title: String?, val entries: List<CoverEntryData>)

@Composable
@RemoteComposable
fun RemoteHaShutter(data: HaShutterCardData, modifier: RemoteModifier = RemoteModifier) {
  CoverCard(data.title, data.entries, modifier) { entry, live ->
    if (entry.garage) GarageDoorDrawing(live) else ShutterWindowDrawing(live)
  }
}
