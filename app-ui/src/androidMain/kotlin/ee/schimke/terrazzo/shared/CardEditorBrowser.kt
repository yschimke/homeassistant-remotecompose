package ee.schimke.terrazzo.shared

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

@Composable
internal actual fun CardEditorBrowser(
  url: String,
  modifier: Modifier,
  onNavigation: (String) -> Unit,
) {
  val context = LocalContext.current
  val navigation by rememberUpdatedState(onNavigation)
  val browser =
    remember(url) {
      WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webViewClient =
          object : WebViewClient() {
            override fun onPageStarted(
              view: WebView?,
              location: String?,
              favicon: android.graphics.Bitmap?,
            ) {
              location?.takeIf { it.startsWith("https://") }?.let(navigation)
            }
          }
        loadUrl(url)
      }
    }
  DisposableEffect(browser) {
    onDispose {
      browser.stopLoading()
      browser.destroy()
    }
  }
  AndroidView(factory = { browser }, modifier = modifier)
}
