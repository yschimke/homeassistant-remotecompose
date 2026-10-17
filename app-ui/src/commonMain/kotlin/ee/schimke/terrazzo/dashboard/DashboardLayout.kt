package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.SectionSpan
import ee.schimke.ha.model.SectionsGrid
import ee.schimke.ha.model.SectionsGridLayout
import ee.schimke.ha.model.ViewHeader

/**
 * Structured projection of a Lovelace [Dashboard] that preserves the pieces the renderer needs to
 * lay out faithfully:
 *
 * - **views** keep their order and titles so multi-view dashboards can render boundaries (most
 *   dashboards have one view; HA tabs map to views, which we don't expose as tabs yet).
 * - **sections** keep their titles. HA's modern dashboard model uses sections as the column unit on
 *   wide screens; we mirror that on Expanded widths and stack-with-headings on Compact.
 * - **orphan cards** are the legacy `view.cards` array used before sections existed. They render as
 *   a single column above any sections in the same view.
 *
 * The previous renderer concatenated all cards from all views and sections into one flat list
 * ([flattenCards] in `DashboardViewScreen`), which threw away every grouping signal HA exposes.
 * This type keeps those signals so the layout layer can reflow them per width class.
 */
data class DashboardLayout(val views: List<ViewLayout>) {
  val sectionCount: Int
    get() = views.sumOf { it.sections.size }

  val isEmpty: Boolean
    get() = views.all { it.orphanCards.isEmpty() && it.sections.isEmpty() && it.header == null }
}

data class ViewLayout(
  val title: String?,
  /**
   * Lovelace's [ee.schimke.ha.model.View.path] — the optional view slug. Used as part of the stable
   * key for pinned sections so that reordering views in HA doesn't silently invalidate pins (the
   * path is the only sort-stable handle a view exposes).
   */
  val viewPath: String,
  val orphanCards: List<CardConfig>,
  val sections: List<SectionLayout>,
  /** The sections view's `header`; its card renders above the sections at full width. */
  val header: ViewHeader? = null,
  /** HA's `max_columns` for the sections grid (null = HA's default of 4). */
  val maxColumns: Int? = null,
  /** HA's `dense_section_placement`. */
  val denseSectionPlacement: Boolean = false,
) {
  /**
   * Place this view's sections in a view [viewWidthDp] wide inside a window [windowWidthDp] wide,
   * with HA's sections-grid rules ([SectionsGrid.layoutInView]: responsive column gap, 24 dp row
   * gap, one column gap of padding either side). [singleTrackMaxWidthDp] caps a layout whose
   * sections all span the full width.
   */
  fun sectionsGrid(
    viewWidthDp: Float,
    windowWidthDp: Float = viewWidthDp,
    singleTrackMaxWidthDp: Float? = null,
  ): SectionsGridLayout =
    SectionsGrid.layoutInView(
      spans = sections.map { SectionSpan(it.columnSpan, it.rowSpan) },
      viewWidthDp = viewWidthDp,
      windowWidthDp = windowWidthDp,
      maxColumns = maxColumns,
      dense = denseSectionPlacement,
      singleTrackMaxWidthDp = singleTrackMaxWidthDp,
    )
}

data class SectionLayout(
  val title: String?,
  val cards: List<CardConfig>,
  /** HA's `column_span` (null = 1), clamped to the grid's columns when placed. */
  val columnSpan: Int? = null,
  /** HA's `row_span` (null = 1). */
  val rowSpan: Int? = null,
)

/**
 * Project a Lovelace dashboard payload onto [DashboardLayout]. View / section / card order is
 * preserved. Empty views (no orphans, no sections) survive the projection — the renderer drops them
 * with [DashboardLayout.isEmpty] handling rather than us pruning here, so a dashboard that
 * legitimately has zero cards still surfaces a "no cards" empty state instead of the loading
 * spinner.
 */
fun buildDashboardLayout(dashboard: Dashboard): DashboardLayout =
  DashboardLayout(
    views =
      dashboard.views.mapIndexed { viewIndex, view ->
        ViewLayout(
          title = view.title,
          viewPath = view.path?.takeIf { it.isNotEmpty() } ?: "view-$viewIndex",
          orphanCards = view.cards,
          sections =
            view.sections.map { section ->
              SectionLayout(
                title = section.title,
                cards = section.cards,
                columnSpan = section.columnSpan,
                rowSpan = section.rowSpan,
              )
            },
          header = view.header?.takeIf { it.card != null },
          maxColumns = view.maxColumns,
          denseSectionPlacement = view.denseSectionPlacement == true,
        )
      }
  )
