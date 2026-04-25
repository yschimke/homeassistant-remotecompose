package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.Dashboard

/**
 * Structured projection of a Lovelace [Dashboard] that preserves the
 * pieces the renderer needs to lay out faithfully:
 *
 *   - **views** keep their order and titles so multi-view dashboards
 *     can render boundaries (most dashboards have one view; HA tabs
 *     map to views, which we don't expose as tabs yet).
 *   - **sections** keep their titles. HA's modern dashboard model uses
 *     sections as the column unit on wide screens; we mirror that on
 *     Expanded widths and stack-with-headings on Compact.
 *   - **orphan cards** are the legacy `view.cards` array used before
 *     sections existed. They render as a single column above any
 *     sections in the same view.
 *
 * The previous renderer concatenated all cards from all views and
 * sections into one flat list ([flattenCards] in `DashboardViewScreen`),
 * which threw away every grouping signal HA exposes. This type keeps
 * those signals so the layout layer can reflow them per width class.
 */
data class DashboardLayout(val views: List<ViewLayout>) {
    val sectionCount: Int get() = views.sumOf { it.sections.size }
    val isEmpty: Boolean get() = views.all { it.orphanCards.isEmpty() && it.sections.isEmpty() }
}

data class ViewLayout(
    val title: String?,
    val orphanCards: List<CardConfig>,
    val sections: List<SectionLayout>,
)

data class SectionLayout(
    val title: String?,
    val cards: List<CardConfig>,
)

/**
 * Project a Lovelace dashboard payload onto [DashboardLayout]. View /
 * section / card order is preserved. Empty views (no orphans, no
 * sections) survive the projection — the renderer drops them with
 * [DashboardLayout.isEmpty] handling rather than us pruning here, so
 * a dashboard that legitimately has zero cards still surfaces a "no
 * cards" empty state instead of the loading spinner.
 */
fun buildDashboardLayout(dashboard: Dashboard): DashboardLayout =
    DashboardLayout(
        views = dashboard.views.map { view ->
            ViewLayout(
                title = view.title,
                orphanCards = view.cards,
                sections = view.sections.map { section ->
                    SectionLayout(title = section.title, cards = section.cards)
                },
            )
        },
    )
