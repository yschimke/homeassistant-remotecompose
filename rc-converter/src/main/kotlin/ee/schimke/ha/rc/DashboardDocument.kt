package ee.schimke.ha.rc

import androidx.compose.runtime.Composable
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.View

/**
 * Entry point: take a fetched [Dashboard] + [HaSnapshot] and emit a single
 * RemoteCompose document per view.
 *
 * NOTE: the concrete `RemoteComposeWriter` call is left for the caller to
 * wire up because its API is in alpha and shifts between releases — see
 * `androidx.compose.remote:remote-creation-compose:1.0.0-alpha08`. Typical
 * usage looks like:
 *
 * ```kotlin
 * val bytes = RemoteComposeWriter(widthPx, heightPx).encodeToByteArray {
 *     DashboardRenderer(registry).RenderView(view, snapshot)
 * }
 * ```
 */
class DashboardRenderer(private val registry: CardRegistry) {
    @Composable
    fun RenderView(view: View, snapshot: HaSnapshot) {
        // Iterate view.sections / view.cards and dispatch each to the
        // registered converter. Placeholder here because layout primitives
        // (RemoteColumn / RemoteFlowLayout / …) come from the alpha API and
        // are imported at the call site.
        view.cards.forEach { card ->
            val converter = registry.get(card.type) ?: return@forEach
            converter.Render(card, snapshot)
        }
    }

    fun unsupportedIn(view: View): List<String> =
        view.cards.map { it.type }.filter { registry.get(it) == null }.distinct()
}
