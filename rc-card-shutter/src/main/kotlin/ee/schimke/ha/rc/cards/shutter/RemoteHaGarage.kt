@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards.shutter

import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable

/**
 * `custom:garage-shutter-card` card data. One row per garage door: name, sectional-door
 * visualisation, up / stop / down buttons, state label. The visualisation renders a horizontal 5:3
 * frame (vs the window shutter's near-square 6:5 frame) with horizontal panel divisions on the door
 * so it reads as garage rather than blinds.
 *
 * Every value a row shows follows its cover's bindings ([CoverLive]): the card is captured once and
 * never re-encodes for a state change (live-card-updates.md, path 1). [title] is the card's
 * `title`, bound as `card.title` in a design template.
 */
data class HaGarageCardData(val title: String?, val entries: List<CoverEntryData>)

@Composable
@RemoteComposable
fun RemoteHaGarage(data: HaGarageCardData, modifier: RemoteModifier = RemoteModifier) {
  CoverCard(data.title, data.entries, modifier) { _, live -> GarageDoorDrawing(live) }
}
