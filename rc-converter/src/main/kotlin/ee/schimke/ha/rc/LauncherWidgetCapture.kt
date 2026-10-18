@file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

package ee.schimke.ha.rc

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.profile.Profile
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asImageBitmap
import ee.schimke.composeai.rcplayer.edit.bitmapUrls
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.LocalPictureImageStrategy
import ee.schimke.ha.rc.components.PictureImageStrategy
import ee.schimke.ha.rc.components.WidgetActionRegistry
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking

/** A direct capture ready for a host without an image loader, with its numeric host actions. */
class LauncherDirectCapture(val bytes: ByteArray, val actions: Map<Int, String>)

/**
 * Capture non-templated cards with self-contained images. Fetch through the embedding app before
 * the final capture; an unavailable image takes the component's icon/name/state fallback.
 */
fun captureLauncherWidgetDocument(
  context: Context,
  card: CardConfig,
  snapshot: HaSnapshot,
  registry: CardRegistry,
  widthPx: Int,
  heightPx: Int,
  densityDpi: Int,
  captureProfile: Profile,
  dark: Boolean,
  look: LauncherLook = LauncherLook(),
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
      CompositionLocalProvider(LocalPictureImageStrategy provides strategy) {
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
        )
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
        fetchImage(url)?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
      }
      // A missing/invalid image uses the converter's supported icon/name/state fallback.
      capture(PictureImageStrategy.Inline { bitmaps[it] })
    }
  }

  return LauncherDirectCapture(captured.bytes, widgetActions.entries.toMap())
}
