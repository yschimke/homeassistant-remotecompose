package ee.schimke.terrazzo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Coarse window-width buckets used by the dashboard layout to decide
 * how aggressively to pack [ee.schimke.ha.rc.CardWidthClass.Compact]
 * cards into a single row. Thresholds match the Material 3 window
 * size class breakpoints (`Compact <600dp`, `Medium 600..839dp`,
 * `Expanded ≥840dp`) — same numbers as `WindowSizeClass`, without the
 * extra dependency surface.
 */
enum class WindowSize {
    /** Phone portrait, foldable closed. */
    Compact,

    /** Phone landscape, small tablets, foldable inner-display in portrait. */
    Medium,

    /** Tablets, foldable unfolded in landscape, desktops. */
    Expanded,
    ;

    companion object {
        fun fromWidthDp(widthDp: Int): WindowSize = when {
            widthDp < 600 -> Compact
            widthDp < 840 -> Medium
            else -> Expanded
        }
    }
}

/**
 * Per-bucket layout parameters consumed by `DashboardViewScreen`.
 *
 * @property compactCardsPerRow how many [CardWidthClass.Compact] cards
 *   fit in one row at this width. Two on a phone is enough to make a
 *   typical "lights / cover / scene" cluster usable; tablets and
 *   foldables get more so a dashboard authored for the wall display
 *   doesn't waste the extra real estate.
 * @property maxContentWidth caps the dashboard column on very wide
 *   screens so a 1280-dp tablet doesn't render a 1280-dp-wide
 *   `entities` list — beyond ~720 dp the list becomes unscannable.
 *   `Dp.Unspecified` means "no cap". Only applied when the dashboard
 *   has no sections (legacy `view.cards`); section columns enforce
 *   their own width via [maxSectionColumns].
 * @property horizontalGutter outer padding either side of the dashboard
 *   column. Bigger on tablets purely for breathing room.
 * @property maxSectionColumns ceiling on how many HA sections render
 *   side-by-side in wide mode. The actual column count is
 *   `min(maxSectionColumns, sectionCount)`. `1` means "always
 *   single-column" — sections still render with their titles, just
 *   stacked vertically.
 */
data class LayoutConfig(
    val windowSize: WindowSize,
    val compactCardsPerRow: Int,
    val maxContentWidth: Dp,
    val horizontalGutter: Dp,
    val maxSectionColumns: Int,
)

private val ExpandedMaxWidth = 840.dp

@Composable
@ReadOnlyComposable
fun rememberLayoutConfig(): LayoutConfig {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return when (WindowSize.fromWidthDp(widthDp)) {
        WindowSize.Compact -> LayoutConfig(
            windowSize = WindowSize.Compact,
            compactCardsPerRow = 2,
            maxContentWidth = Dp.Unspecified,
            horizontalGutter = 12.dp,
            maxSectionColumns = 1,
        )
        WindowSize.Medium -> LayoutConfig(
            windowSize = WindowSize.Medium,
            compactCardsPerRow = 3,
            maxContentWidth = Dp.Unspecified,
            horizontalGutter = 16.dp,
            // Medium is 600..839 dp — splitting 3 sections across that
            // would give ~260 dp / col which is below the ~320 dp
            // floor most HA cards read well at. Keep single-column;
            // section titles still preserve structure.
            maxSectionColumns = 1,
        )
        WindowSize.Expanded -> LayoutConfig(
            windowSize = WindowSize.Expanded,
            compactCardsPerRow = 4,
            // No per-content cap when sections drive the layout — the
            // section grid handles width. The cap kicks in for legacy
            // sectionless dashboards via `widthIn(max = …)`.
            maxContentWidth = ExpandedMaxWidth,
            horizontalGutter = 24.dp,
            // 2 columns at 840 dp (≈400 dp / col), 3 columns at
            // 1280 dp (≈400 dp / col). Beyond 3 the diminishing
            // returns aren't worth the eyeline-skip cost; the
            // renderer caps further with `min(this, sectionCount)`.
            maxSectionColumns = if (widthDp >= 1200) 3 else 2,
        )
    }
}
