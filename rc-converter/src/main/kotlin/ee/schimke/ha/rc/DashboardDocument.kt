package ee.schimke.ha.rc

import androidx.compose.runtime.Composable
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.View

/**
 * Entry point: take a fetched [Dashboard] + [HaSnapshot] and emit a single
 * RemoteCompose document per view. Installs the registry as a composition
 * local so stack/grid/conditional cards can recurse via `RenderChild`.
 */
class DashboardRenderer(private val registry: CardRegistry) {
    @Composable
    fun RenderView(view: View, snapshot: HaSnapshot) {
        ProvideCardRegistry(registry) {
            view.cards.forEach { card -> RenderChild(card, snapshot) }
        }
    }

    fun unsupportedIn(view: View): List<String> =
        view.cards.map { it.type }.filter { registry.get(it) == null }.distinct()
}
