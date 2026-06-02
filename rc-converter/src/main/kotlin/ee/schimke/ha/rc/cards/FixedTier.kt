@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.runtime.Composable

/**
 * Self-centre a **box/column-rooted** Fixed-mode `Full` tier (the tile, statistic and sensor cards)
 * inside the cell it's handed.
 *
 * These full composables are `fillMaxWidth` + wrap-height, so on a cell taller than their natural
 * shape (e.g. a `tile` rendered into a `3×2` widget) they glue to the top edge and leave the lower
 * half blank — the Principle 8 "no dead space" / "earn the canvas" failure called out in
 * `docs/architecture/adaptive-card-layouts.md`. Centring is the cheapest correct version of "earn
 * the canvas" (Principle 7); a card promotes a real Expanded tier once the single-breakpoint limit
 * (#224) lifts.
 *
 * Centring is done with weighted spacers in a **default-arrangement** [RemoteColumn], not a wrapper
 * `contentAlignment = Center` — the latter is a no-op for the `fillMaxWidth` children these tiers
 * use in alpha010.
 *
 * Row-rooted full tiers (`RemoteHaEntityRow`) do **not** go through this helper: a `fillMaxWidth`
 * wrap-height row measures to zero height under the filled-height constraint a centring column
 * hands down and vanishes (a box root survives, which is why this helper is box-only). Those tiers
 * instead take `fillMaxSize` and self-centre via their own `verticalAlignment = CenterVertically`,
 * the way `RemoteHaGaugeWide` does.
 */
@Composable
internal fun CenteredCell(content: @Composable () -> Unit) {
  RemoteColumn(modifier = RemoteModifier.fillMaxSize()) {
    RemoteBox(modifier = RemoteModifier.fillMaxWidth().weight(1f))
    content()
    RemoteBox(modifier = RemoteModifier.fillMaxWidth().weight(1f))
  }
}
