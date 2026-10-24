@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.runtime.Composable

/**
 * What a card design capture runs in: an Android `Context` on Android, where AndroidX's capture
 * needs one; on the JVM, a [JvmCaptureHost] naming the operations the documents may use.
 */
expect abstract class CaptureHost

/** The Remote Compose document API level captures write: the mobile app template's level. */
internal expect val DOCUMENT_API_LEVEL: Int

/**
 * A card design template's bytes: [content] captured at [display]'s canvas, with host density
 * (every dp and sp reads the player's display) in the dashboard's wrap-mode profile.
 */
internal expect suspend fun captureTemplateDocument(
  host: CaptureHost,
  display: RemoteCreationDisplayInfo,
  content: @Composable @RemoteComposable () -> Unit,
): ByteArray

internal expect fun logWarning(tag: String, message: String, error: Throwable)
