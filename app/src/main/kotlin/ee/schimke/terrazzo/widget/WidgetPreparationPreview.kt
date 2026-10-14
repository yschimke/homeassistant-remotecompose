@file:Suppress("RestrictedApi")

package ee.schimke.terrazzo.widget

import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cardSizeConstraints
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withEnhancedShutter
import ee.schimke.ha.rc.formatState
import ee.schimke.terrazzo.dashboard.LauncherGrid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Renders the actual launcher document, recaptured at each selected size. */
@Composable
internal fun WidgetPreparationPreview(
  baseUrl: String,
  card: CardConfig,
  snapshot: HaSnapshot,
  onPreview: (RemoteViews?) -> Unit,
) {
  val context = LocalContext.current
  val focusManager = LocalFocusManager.current
  val keyboard = LocalSoftwareKeyboardController.current
  val entityId = card.raw["entity"]?.jsonPrimitive?.contentOrNull
  val entity = snapshot.states[entityId]
  val label =
    card.raw["name"]?.jsonPrimitive?.contentOrNull
      ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.contentOrNull
      ?: "${card.type} widget"
  val stateLabel = entity?.let { formatState(it) }.orEmpty()
  if (android.os.Build.VERSION.SDK_INT < WidgetInstaller.MIN_HOST_API) {
    Text("$label · $stateLabel", style = MaterialTheme.typography.titleMedium)
    return
  }
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
    // Coalesce typing/drag steps and keep capture/image loading off the UI thread.
    delay(100)
    views =
      try {
        withContext(Dispatchers.Default) {
          TerrazzoWidgetProvider()
            .renderPreview(
              context,
              baseUrl,
              card,
              snapshot,
              WidgetSizeDp(width * LAUNCHER_CELL_WIDTH_DP, height * LAUNCHER_CELL_HEIGHT_DP),
            )
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        android.util.Log.e(
          "WidgetPreparationPreview",
          "Could not capture launcher preview",
          failure,
        )
        null
      }
    failed = views == null
    latestOnPreview(views)
  }
  Text("Try it on your home screen", style = MaterialTheme.typography.titleMedium)
  Text(
    "Adaptive layout · drag the corner or try a size. New widgets start at Default; resize them on the home screen.",
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
            focusManager.clearFocus()
            keyboard?.hide()
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
            runCatching { inflateWidgetPreview(ctx, current) }
              .getOrElse { failure ->
                android.util.Log.e(
                  "WidgetPreparationPreview",
                  "Could not inflate launcher preview",
                  failure,
                )
                views = null
                failed = true
                latestOnPreview(null)
                FrameLayout(ctx)
              }
              .also { frame ->
                // Expose a read-only description through Compose, not inactive virtual click
                // targets.
                frame.importantForAccessibility =
                  android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
              }
          },
          modifier =
            Modifier.fillMaxSize().testTag("widget-render").semantics {
              contentDescription = "$label preview, $stateLabel, $width by $height cells"
            },
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

/** Native RemoteViews inflation, shared by both in-app previews and the launcher-parity test. */
internal fun inflateWidgetPreview(
  context: android.content.Context,
  views: RemoteViews,
): FrameLayout =
  FrameLayout(context).apply {
    addView(views.apply(context, this), FrameLayout.LayoutParams(-1, -1))
  }
