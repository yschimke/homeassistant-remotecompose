package ee.schimke.ha.rc

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import ee.schimke.composeai.rccreation.compose.capture.RemoteCreationDisplayInfo
import ee.schimke.composeai.rccreation.compose.capture.RemoteDensity
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import java.io.ByteArrayOutputStream

/** A captured Remote Compose document: its serialized `.rc` [bytes]. */
class CapturedDocument(val bytes: ByteArray)

/**
 * [content] captured headlessly for [profile]'s player, with rc-players' Compose Multiplatform
 * creation API ([captureRemoteDocument]). It runs a composition with no display, so it is safe from
 * a WorkManager worker; [context] gives the display it is captured for and is what the content
 * reads through `LocalContext`.
 */
suspend fun captureSingleRemoteDocument(
  context: Context,
  creationDisplayInfo: RemoteCreationDisplayInfo = context.creationDisplayInfo(),
  remoteDensity: RemoteDensity? = null,
  profile: CaptureProfile = androidX,
  content: @Composable @RemoteComposable () -> Unit,
): CapturedDocument =
  CapturedDocument(
    captureRemoteDocument(
      creationDisplayInfo = creationDisplayInfo,
      profile = profile,
      remoteDensity = remoteDensity,
      encodePng = ::encodePng,
    ) {
      CompositionLocalProvider(
        LocalContext provides context,
        LocalConfiguration provides context.resources.configuration,
        content = content,
      )
    },
  )

/** This context's display: its size in pixels, density and font scale. */
fun Context.creationDisplayInfo(): RemoteCreationDisplayInfo {
  val metrics = resources.displayMetrics
  return RemoteCreationDisplayInfo(
    width = metrics.widthPixels.coerceAtLeast(1),
    height = metrics.heightPixels.coerceAtLeast(1),
    densityDpi = metrics.densityDpi,
    fontScale = resources.configuration.fontScale,
  )
}

private fun encodePng(bitmap: ImageBitmap): ByteArray? =
  ByteArrayOutputStream()
    .takeIf { bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    ?.toByteArray()
