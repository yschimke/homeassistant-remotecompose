package ee.schimke.terrazzo.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import ee.schimke.terrazzo.shared.TerrazzoMultiplatformApp

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  ComposeViewport { TerrazzoMultiplatformApp() }
}
