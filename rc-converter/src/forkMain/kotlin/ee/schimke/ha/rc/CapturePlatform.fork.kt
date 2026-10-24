@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import androidx.compose.remote.creation.common.CoreDocument
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.RemoteDensity
import androidx.compose.remote.creation.compose.capture.captureCommonRemoteDocument
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.runtime.Composable

actual abstract class CaptureHost {
  /** The Remote Compose opcodes a captured document may use: what its player supports. */
  abstract val supportedOperations: Set<Int>
}

/** A capture outside Android whose documents may use [supportedOperations]. */
class CommonCaptureHost(override val supportedOperations: Set<Int>) : CaptureHost()

internal actual val DOCUMENT_API_LEVEL: Int = CoreDocument.DOCUMENT_API_LEVEL

internal actual suspend fun captureTemplateDocument(
  host: CaptureHost,
  display: RemoteCreationDisplayInfo,
  content: @Composable @RemoteComposable () -> Unit,
): ByteArray =
  captureCommonRemoteDocument(
    creationDisplayInfo = display,
    remoteDensity = RemoteDensity.Host,
    supportedOperations = host.supportedOperations,
    content = content,
  )

internal actual fun logWarning(tag: String, message: String, error: Throwable) {
  println("W/$tag: $message\n${error.stackTraceToString()}")
}
