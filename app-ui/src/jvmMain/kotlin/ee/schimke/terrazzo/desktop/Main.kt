package ee.schimke.terrazzo.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ee.schimke.terrazzo.shared.DesktopAgentGrants
import ee.schimke.terrazzo.shared.TerrazzoMultiplatformApp

fun main() {
  Runtime.getRuntime().addShutdownHook(Thread { dev.datlag.kcef.KCEF.disposeBlocking() })
  // A connected agent and running sessions survive closing the app.
  DesktopAgentGrants.use()
  application {
    Window(
      onCloseRequest = ::exitApplication,
      title = "Terrazzo",
      state = rememberWindowState(width = 1200.dp, height = 850.dp),
    ) {
      TerrazzoMultiplatformApp()
    }
  }
}
