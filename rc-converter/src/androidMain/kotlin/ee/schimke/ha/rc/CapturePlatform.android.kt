@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import android.content.Context
import android.util.Log
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.RemoteDensity
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.runtime.Composable

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
