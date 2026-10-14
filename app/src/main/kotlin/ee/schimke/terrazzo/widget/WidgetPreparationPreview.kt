@file:Suppress("RestrictedApi")

package ee.schimke.terrazzo.widget

import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cardSizeConstraints
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withEnhancedShutter
import ee.schimke.terrazzo.dashboard.LauncherGrid

/** Renders the actual launcher document, recaptured at each selected size. */
@Composable
internal fun WidgetPreparationPreview(
  baseUrl: String,
  card: CardConfig,
  snapshot: HaSnapshot,
  onPreview: (RemoteViews?) -> Unit,
) {
  val context = LocalContext.current
  val registry = remember { defaultRegistry().withEnhancedShutter() }
  val bounds =
    remember(card) {
      WidgetSizeClass.forConstraints(registry.cardSizeConstraints(card, snapshot))
        .gridBounds(context)
    }
  var width by remember(bounds) { mutableIntStateOf(bounds.defaultCellsW) }
  var height by remember(bounds) { mutableIntStateOf(bounds.defaultCellsH) }
  var views by remember { mutableStateOf<RemoteViews?>(null) }
  var failed by remember { mutableStateOf(false) }
  // Do not keep an older card's document enabled while a new one is being captured.
  val latestOnPreview by rememberUpdatedState(onPreview)
  LaunchedEffect(card, snapshot, width, height) {
    views = null
    latestOnPreview(null)
    failed = false
    views =
      runCatching {
        TerrazzoWidgetProvider()
          .renderPreview(
            context,
            baseUrl,
            card,
            snapshot,
            WidgetSizeDp(width * LAUNCHER_CELL_WIDTH_DP, height * LAUNCHER_CELL_HEIGHT_DP),
          )
      }
        .getOrNull()
    failed = views == null
    latestOnPreview(views)
  }
  Text("Try it on your home screen", style = MaterialTheme.typography.titleMedium)
  Text(
    "Adaptive layout · drag the corner or try a size. Your launcher chooses the final position.",
    style = MaterialTheme.typography.bodySmall,
  )
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    listOf(
        Triple("Compact", bounds.minCellsW, bounds.minCellsH),
        Triple("Default", bounds.defaultCellsW, bounds.defaultCellsH),
        Triple("Roomy", bounds.maxCellsW, bounds.maxCellsH),
      )
      .forEach { (label, w, h) ->
        FilterChip(
          selected = width == w && height == h,
          onClick = {
            width = w
            height = h
          },
          label = { Text(label) },
        )
      }
  }
  Text("$width × $height cells", style = MaterialTheme.typography.labelLarge)
  LauncherGrid(
    bounds,
    width,
    height,
    { w, h ->
      width = w
      height = h
    },
    MaterialTheme.colorScheme.outlineVariant,
    MaterialTheme.colorScheme.tertiary,
    MaterialTheme.colorScheme.secondary,
    MaterialTheme.colorScheme.primary,
  ) {
    val current = views
    if (current != null) {
      key(current) {
        AndroidView(
          factory = { ctx ->
            FrameLayout(ctx).apply {
              runCatching { addView(current.apply(ctx, this), FrameLayout.LayoutParams(-1, -1)) }
                .onFailure {
                  views = null
                  failed = true
                  latestOnPreview(null)
                }
            }
          },
          modifier = Modifier.fillMaxSize().testTag("widget-render"),
        )
      }
    } else if (failed) {
      Text(
        "This card could not be rendered as a widget. Edit it or choose another card.",
        modifier = Modifier.padding(12.dp),
      )
    } else CircularProgressIndicator(modifier = Modifier.padding(12.dp))
  }
}
