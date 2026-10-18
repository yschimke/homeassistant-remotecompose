@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.terrazzo.shared

import androidx.compose.ui.platform.UriHandler

internal actual val feedbackPlatform: String = "Web (Wasm)"

// Keep this synchronous with the user's click so browsers allow the popup. Open a blank
// window first so blocked popups can be detected, then remove its access to the app.
@JsFun(
  """(url) => {
    const tab = window.open('', '_blank');
    if (!tab) return false;
    tab.opener = null;
    const link = tab.document.createElement('a');
    link.href = url;
    link.rel = 'noreferrer';
    link.referrerPolicy = 'no-referrer';
    tab.document.body.appendChild(link);
    link.click();
    return true;
  }"""
)
private external fun openFeedbackTab(url: String): Boolean

internal actual fun openFeedbackUrl(url: String, uriHandler: UriHandler): Boolean = runCatching {
  openFeedbackTab(url)
}
  .getOrDefault(false)
