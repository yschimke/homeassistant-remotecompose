package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth as rcFillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CachedCardPreview
import ee.schimke.ha.rc.CardPlayback
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.ProvideCardSizeMode
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.cardDataSignature
import ee.schimke.ha.rc.cardSizeConstraints
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withEnhancedShutter
import ee.schimke.ha.rc.components.ProvideCardChrome
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.RemoteHaWidgetSurface
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.haThemeFor
import ee.schimke.ha.rc.image.CoilBitmapLoader
import ee.schimke.ha.rc.widgetsProfile
import ee.schimke.terrazzo.LocalHaImageStack
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.ui.LocalIsDarkTheme
import ee.schimke.terrazzo.ui.LocalThemeStyle
import ee.schimke.terrazzo.widget.WidgetSizeClass

/** Embedded card preview for Android 15, where native launcher widgets are unavailable. */
@Composable
internal fun LegacyCardPreview(session: HaSession, card: CardConfig, snapshot: HaSnapshot) {
  val context = LocalContext.current
  val registry = remember { defaultRegistry().withEnhancedShutter() }
  val style = LocalThemeStyle.current
  val dark = LocalIsDarkTheme.current
  val haTheme = remember(style, dark) { haThemeFor(style, dark) }
  val haImageStack = LocalHaImageStack.current
  val imageLoader =
    haImageStack?.imageLoader
      ?: remember(context) { SingletonImageLoader.get(context.applicationContext) }
  val bitmapLoader =
    remember(context, session.baseUrl, imageLoader) {
      CoilBitmapLoader(
        context.applicationContext,
        imageLoader = imageLoader,
        baseUrl = session.baseUrl,
      )
    }

  // Quantise the card's continuous size hint onto the launcher size
  // class, then read its resize range in whole cells from the same
  // appwidget-provider metadata the launcher uses.
  val sizeClass =
    remember(card, snapshot) {
      WidgetSizeClass.forConstraints(registry.cardSizeConstraints(card, snapshot))
    }
  val bounds = remember(sizeClass, context) { sizeClass.gridBounds(context) }

  // Current slot, in cells. Starts at the comfortable default the
  // widget pins at; resized by dragging the corner handle.
  var cellsW by remember(sizeClass) { mutableIntStateOf(bounds.defaultCellsW) }
  var cellsH by remember(sizeClass) { mutableIntStateOf(bounds.defaultCellsH) }

  val gridColor = MaterialTheme.colorScheme.outlineVariant
  val smallestColor = MaterialTheme.colorScheme.tertiary
  val largestColor = MaterialTheme.colorScheme.secondary
  val currentColor = MaterialTheme.colorScheme.primary

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    LauncherGrid(
      bounds = bounds,
      cellsW = cellsW,
      cellsH = cellsH,
      onResize = { w, h ->
        cellsW = w
        cellsH = h
      },
      gridColor = gridColor,
      smallestColor = smallestColor,
      largestColor = largestColor,
      currentColor = currentColor,
    ) {
      CachedCardPreview(
        // Size-independent: the captured document reflows to the slot
        // at playback via its runtime size-breakpoints, so the same
        // bytes serve every cell size — keying on cellsW/cellsH would
        // only force a redundant (blocking) re-encode on every drag step.
        cacheKey = LegacyPreviewKey(card, style, dark),
        // Android 15 cannot host native layout widgets. Keep its embedded card preview.
        profile = widgetsProfile,
        card = card,
        snapshot = snapshot,
        liveBindings = registry.cardDataSignature(card, snapshot) == null,
        bitmapLoader = bitmapLoader,
        modifier = Modifier.fillMaxSize(),
        playback = CardPlayback.LauncherWidget,
      ) {
        ProvideCardRegistry(registry) {
          ProvideHaTheme(haTheme) {
            // The same fixed-size, full-bleed surface the
            // launcher widget wraps the card in (no in-app
            // card chrome), so the preview matches the slot.
            ProvideCardSizeMode(CardSizeMode.Fixed) {
              ProvideCardChrome(enabled = false) {
                RemoteHaWidgetSurface {
                  RenderChild(card, snapshot, RemoteModifier.rcFillMaxWidth())
                }
              }
            }
          }
        }
      }
    }

    ResizeLegend(
      bounds = bounds,
      cellsW = cellsW,
      cellsH = cellsH,
      smallestColor = smallestColor,
      largestColor = largestColor,
      currentColor = currentColor,
    )
  }
}

private data class LegacyPreviewKey(
  val card: CardConfig,
  val style: ThemeStyle,
  val dark: Boolean,
)
