@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.terrazzo.web

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import ee.schimke.terrazzo.shared.*

@JsFun(
  "() => /\\/demo\\/?$/.test(location.pathname) || new URLSearchParams(location.search).get('demo') === '1' ? 'demo' : 'app'"
)
private external fun entryMode(): String

@JsFun("() => location.origin + '/demo-ha'") private external fun demoBackendUrl(): String

@JsFun("() => new URLSearchParams(location.search).get('data') === 'fake'")
private external fun fakeDataRequested(): Boolean

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  ComposeViewport(viewportContainerId = "app", configure = { enableBrowserWindowInsets = true }) {
    when (entryMode()) {
      "demo" -> PublicDemo()
      else -> TerrazzoMultiplatformApp()
    }
  }
}

@Composable
private fun PublicDemo() {
  var mode by remember {
    mutableStateOf(if (fakeDataRequested()) DemoDataMode.FAKE else DemoDataMode.AUTO)
  }
  val source = remember { PublicDemoDataSource(demoBackendUrl()) }
  DisposableEffect(source) { onDispose { source.close() } }
  val frame by
    remember(mode, source) { demoFrames(mode, source) }
      .collectAsState(DemoFrame(DemoData.Fake, DemoDataStatus.FAKE))
  DashboardExperience(
    frame.data.dashboard,
    frame.data.snapshot,
    dataStatus = frame.status,
    dataMode = mode,
    onDataModeChange = { mode = it },
  )
}
