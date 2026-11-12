@file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

package ee.schimke.ha.rc

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asImageBitmap
import ee.schimke.composeai.rccreation.compose.capture.RemoteCreationDisplayInfo
import ee.schimke.composeai.rcplayer.edit.bitmapUrls
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.LocalPictureImageStrategy
import ee.schimke.ha.rc.components.PictureImageStrategy
import ee.schimke.ha.rc.components.ProvideExpressionNames
import ee.schimke.ha.rc.components.WidgetActionRegistry
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking

/**
 * A direct capture ready for a host without an image loader, with its numeric host actions and, of
 * those, the ones a locked card holds back ([WidgetActionRegistry.lockedEntries]).
 */
class LauncherDirectCapture(
  val bytes: ByteArray,
  val actions: Map<Int, String>,
  val lockedActions: Set<Int> = emptySet(),
)

/**
 * Capture non-templated cards with self-contained images. Fetch through the embedding app before
 * the final capture; an unavailable image takes the component's icon/name/state fallback. With a
 * [fontFilter] that [LauncherFontFilter.readsContents], the card fonts are kept and drawn from
 * bitmap fonts; a capture it can't take is captured again without them.
 */
fun captureLauncherWidgetDocument(
  context: Context,
  card: CardConfig,
  snapshot: HaSnapshot,
  registry: CardRegistry,
  widthPx: Int,
  heightPx: Int,
  densityDpi: Int,
  captureProfile: CaptureProfile,
  dark: Boolean,
  look: LauncherLook = LauncherLook(),
  fontFilter: LauncherFontFilter? = null,
  fetchImage: (String) -> ByteArray?,
): LauncherDirectCapture {
  // The card's own contents: only a filter that keeps them on the device may see them.
  if (fontFilter != null && fontFilter.readsContents) {
    val withFonts =
      captureDirect(
        context,
        card,
        snapshot,
        registry,
        widthPx,
        heightPx,
        densityDpi,
        captureProfile,
        dark,
        look,
        cardFonts = true,
        fetchImage,
      )
    fontFilter.filter(withFonts.bytes, densityDpi)?.let {
      return LauncherDirectCapture(it, withFonts.actions, withFonts.lockedActions)
    }
  }
  return captureDirect(
    context,
    card,
    snapshot,
    registry,
    widthPx,
    heightPx,
    densityDpi,
    captureProfile,
    dark,
    look,
    cardFonts = false,
    fetchImage,
  )
}

private fun captureDirect(
  context: Context,
  card: CardConfig,
  snapshot: HaSnapshot,
  registry: CardRegistry,
  widthPx: Int,
  heightPx: Int,
  densityDpi: Int,
  captureProfile: CaptureProfile,
  dark: Boolean,
  look: LauncherLook,
  cardFonts: Boolean,
  fetchImage: (String) -> ByteArray?,
): LauncherDirectCapture {
  val widgetActions =
    WidgetActionRegistry(snapshot.states.mapValues { (_, entity) -> formatState(entity, snapshot) })
  fun capture(strategy: PictureImageStrategy) = runBlocking {
    captureSingleRemoteDocument(
      context = context,
      creationDisplayInfo = RemoteCreationDisplayInfo(widthPx, heightPx, densityDpi),
      profile = captureProfile,
    ) {
      // One card per document, so its expressions are named in composition order, as its template's
      // are (ProvideExpressionNames).
      CompositionLocalProvider(LocalPictureImageStrategy provides strategy) {
        ProvideExpressionNames {
          LauncherWidgetContent(
            registry,
            captureProfile,
            dark,
            widgetActions,
            card,
            snapshot,
            WidgetCaptureSizeDp(
              (widthPx * 160f / densityDpi).roundToInt(),
              (heightPx * 160f / densityDpi).roundToInt(),
            ),
            look,
            cardFonts,
          )
        }
      }
    }
  }
  val captured = run {
    // Discover what the converter actually emitted, including nested cards and non-templated
    // configs (camera_view, camera_image, aspect_ratio, tap_action, ...). Never send this URL
    // document to the launcher: its player has neither the HA image stack nor credentials.
    val initial = capture(PictureImageStrategy.DefaultAppUrl)
    val urls = RcDocumentCodec.decode(initial.bytes, RcWireLimits()).bitmapUrls
    if (urls.isEmpty()) initial
    else {
      val bitmaps = urls.associateWith { url ->
        fetchImage(url)?.let { decodeBoundedBitmap(it)?.asImageBitmap() }
      }
      // A missing/invalid image uses the converter's supported icon/name/state fallback.
      capture(PictureImageStrategy.Inline { bitmaps[it] })
    }
  }

  return LauncherDirectCapture(
    captured.bytes,
    widgetActions.entries.toMap(),
    widgetActions.lockedEntries,
  )
}
