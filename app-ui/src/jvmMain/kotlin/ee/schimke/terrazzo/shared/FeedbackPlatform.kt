package ee.schimke.terrazzo.shared

import androidx.compose.ui.platform.UriHandler

internal actual val feedbackPlatform: String = "Desktop"

internal actual fun openFeedbackUrl(url: String, uriHandler: UriHandler): Boolean = runCatching {
  uriHandler.openUri(url)
}
  .isSuccess
