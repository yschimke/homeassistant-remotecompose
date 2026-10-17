package ee.schimke.terrazzo.ui

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import ee.schimke.ha.model.SectionsGrid

/**
 * Coarse window-width buckets used by the dashboard layout to decide how aggressively to pack
 * [ee.schimke.ha.rc.CardWidthClass.Compact] cards into a single row. Thresholds match the Material
 * 3 window size class breakpoints (`Compact <600dp`, `Medium 600..839dp`, `Expanded ≥840dp`) — same
 * numbers as `WindowSizeClass`, without the extra dependency surface.
 */
enum class WindowSize {
  /** Phone portrait, foldable closed. */
  Compact,

  /** Phone landscape, small tablets, foldable inner-display in portrait. */
  Medium,

  /** Tablets, foldable unfolded in landscape, desktops. */
  Expanded;

  companion object {
    fun fromWidthDp(widthDp: Int): WindowSize =
      when {
        widthDp < 600 -> Compact
        widthDp < 840 -> Medium
        else -> Expanded
      }
  }
}

/**
 * Per-bucket layout parameters consumed by `DashboardViewScreen`.
 *
 * @property compactCardsPerRow how many [CardWidthClass.Compact] cards fit in one row at this
 *   width. Two on a phone is enough to make a typical "lights / cover / scene" cluster usable;
 *   tablets and foldables get more so a dashboard authored for the wall display doesn't waste the
 *   extra real estate.
 * @property maxContentWidth caps content that renders as one column — legacy `view.cards`, and a
 *   sections view whose sections all span the full width — so a landscape phone or a 1280-dp tablet
 *   doesn't stretch a tile or an `entities` list across the screen. `Dp.Unspecified` means "no cap"
 *   (phones). The values are [SectionsGrid.readableMaxWidthDp]'s, shared with the preview harness.
 *   How many sections sit side by side is HA's rule, not a per-bucket setting: see [SectionsGrid].
 * @property horizontalGutter outer padding either side of the dashboard column. Bigger on tablets
 *   purely for breathing room.
 */
data class LayoutConfig(
  val windowSize: WindowSize,
  val compactCardsPerRow: Int,
  val maxContentWidth: Dp,
  val horizontalGutter: Dp,
)

@Composable
fun rememberLayoutConfig(): LayoutConfig {
  val window = currentWindowAdaptiveInfoV2().windowSizeClass
  val bucket =
    when {
      window.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) ->
        WindowSize.Expanded
      window.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) ->
        WindowSize.Medium
      else -> WindowSize.Compact
    }
  return when (bucket) {
    WindowSize.Compact ->
      LayoutConfig(
        windowSize = WindowSize.Compact,
        compactCardsPerRow = 2,
        maxContentWidth = Dp.Unspecified,
        horizontalGutter = 12.dp,
      )
    WindowSize.Medium ->
      LayoutConfig(
        windowSize = WindowSize.Medium,
        compactCardsPerRow = 3,
        maxContentWidth = SectionsGrid.MEDIUM_MAX_CONTENT_WIDTH_DP.dp,
        horizontalGutter = 16.dp,
      )
    WindowSize.Expanded ->
      LayoutConfig(
        windowSize = WindowSize.Expanded,
        compactCardsPerRow = 4,
        maxContentWidth = SectionsGrid.EXPANDED_MAX_CONTENT_WIDTH_DP.dp,
        horizontalGutter = 24.dp,
      )
  }
}
