@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import android.content.Context
import android.util.Log
import androidx.compose.remote.core.CoreDocument
import androidx.compose.runtime.Composable
import ee.schimke.composeai.rccreation.compose.capture.RemoteCreationDisplayInfo
import ee.schimke.composeai.rccreation.compose.capture.RemoteDensity
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable

actual typealias CaptureHost = Context

internal actual val DOCUMENT_API_LEVEL: Int = CoreDocument.DOCUMENT_API_LEVEL

internal actual suspend fun captureTemplateDocument(
  host: CaptureHost,
  display: RemoteCreationDisplayInfo,
  content: @Composable @RemoteComposable () -> Unit,
): ByteArray =
  captureSingleRemoteDocument(
      context = host,
      creationDisplayInfo = display,
      remoteDensity = RemoteDensity.Host,
      profile = androidXExperimentalWrap,
      content = content,
    )
    .bytes

internal actual fun logWarning(tag: String, message: String, error: Throwable) {
  Log.w(tag, message, error)
}
