@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import androidx.collection.MutableIntSet
import androidx.compose.runtime.Composable
import ee.schimke.composeai.rccreation.common.CoreDocument
import ee.schimke.composeai.rccreation.common.Profile
import ee.schimke.composeai.rccreation.common.RcProfiles
import ee.schimke.composeai.rccreation.compose.capture.Profile
import ee.schimke.composeai.rccreation.compose.capture.RemoteCreationDisplayInfo
import ee.schimke.composeai.rccreation.compose.capture.RemoteDensity
import ee.schimke.composeai.rccreation.compose.capture.captureCommonRemoteDocument
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable

actual abstract class CaptureHost {
  /** The Remote Compose opcodes a captured document may use: what its player supports. */
  abstract val supportedOperations: Set<Int>
}

/** A capture outside Android whose documents may use [supportedOperations]. */
class CommonCaptureHost(override val supportedOperations: Set<Int>) : CaptureHost()

/**
 * The AndroidX player with its experimental operations, as the app's own capture declares, held to
 * this host's operations: the writer refuses any other.
 */
private fun CaptureHost.profile(): Profile =
  Profile(
    profileFlags = RcProfiles.PROFILE_ANDROIDX or RcProfiles.PROFILE_EXPERIMENTAL,
    supportedOperations = MutableIntSet().also { set -> supportedOperations.forEach(set::add) },
  )

internal actual val DOCUMENT_API_LEVEL: Int = CoreDocument.DOCUMENT_API_LEVEL

internal actual suspend fun captureTemplateDocument(
  host: CaptureHost,
  display: RemoteCreationDisplayInfo,
  content: @Composable @RemoteComposable () -> Unit,
): ByteArray =
  captureCommonRemoteDocument(
    creationDisplayInfo = display,
    remoteDensity = RemoteDensity.Host,
    profile = host.profile(),
    content = content,
  )

internal actual fun logWarning(tag: String, message: String, error: Throwable) {
  println("W/$tag: $message\n${error.stackTraceToString()}")
}
