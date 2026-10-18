package ee.schimke.terrazzo.shared

import androidx.compose.ui.platform.UriHandler

internal actual val feedbackPlatform: String = "Android"

internal actual fun openFeedbackUrl(url: String, uriHandler: UriHandler): Boolean = runCatching {
  uriHandler.openUri(url)
}
  .isSuccess
