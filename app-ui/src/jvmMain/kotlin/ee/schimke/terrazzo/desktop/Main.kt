package ee.schimke.terrazzo.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ee.schimke.terrazzo.shared.TerrazzoMultiplatformApp

fun main() = application {
  Window(
    onCloseRequest = ::exitApplication,
    title = "Terrazzo",
    state = rememberWindowState(width = 1200.dp, height = 850.dp),
  ) {
    TerrazzoMultiplatformApp()
  }
}
