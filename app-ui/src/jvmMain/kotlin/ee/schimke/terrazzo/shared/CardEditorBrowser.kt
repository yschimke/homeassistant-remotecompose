package ee.schimke.terrazzo.shared

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.multiplatform.webview.web.WebView
import com.multiplatform.webview.web.rememberWebViewState
import dev.datlag.kcef.KCEF
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Initialisation belongs to the process, so closing a card cannot interrupt a runtime install. */
private object DesktopCardEditorRuntime {
  var ready by mutableStateOf(false)
  var status by mutableStateOf("Preparing the embedded editor…")
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var started = false

  @Synchronized
  fun start() {
    if (started) return
    started = true
    scope.launch {
      try {
        val directory = File(System.getProperty("user.home"), ".terrazzo/browser")
        KCEF.init(
          builder = {
            installDir(File(directory, "runtime"))
            settings { cachePath = File(directory, "cache").absolutePath }
            progress {
              onDownloading {
                status = "Preparing the embedded editor: ${it.toInt().coerceIn(0, 100)}%"
              }
            }
          },
          onError = { status = "The embedded editor could not start. Use Open in browser." },
          onRestartRequired = {
            status = "Restart Terrazzo to use the embedded editor, or use Open in browser."
          },
        )
        // Also covers an already initialised KCEF and a bundled JBR runtime: neither runs the
        // builder's onInitialized callback. Reopening a card should immediately use that runtime.
        val probe = KCEF.newClientOrNull()
        if (probe != null) {
          probe.dispose()
          ready = true
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        status = "The embedded editor could not start. Use Open in browser."
      }
    }
  }
}

@Composable
internal actual fun CardEditorBrowser(
  url: String,
  modifier: Modifier,
  onNavigation: (String) -> Unit,
) {
  LaunchedEffect(Unit) { DesktopCardEditorRuntime.start() }
  if (DesktopCardEditorRuntime.ready) {
    val state = rememberWebViewState(url)
    val navigation by rememberUpdatedState(onNavigation)
    LaunchedEffect(state.lastLoadedUrl) {
      state.lastLoadedUrl?.takeIf { it.startsWith("https://") }?.let(navigation)
    }
    WebView(state, modifier = modifier)
  } else Text(DesktopCardEditorRuntime.status, modifier)
}
