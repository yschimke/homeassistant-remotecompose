package ee.schimke.terrazzo.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.terrazzo.shared.AppChromeTheme
import ee.schimke.terrazzo.shared.ConnectionScreen

@Preview(widthDp = 640, heightDp = 480)
@Composable
internal fun DesktopConnectionPreview() {
  AppChromeTheme("classic", dark = false, host = null) {
    ConnectionScreen(busy = false, error = null, signIn = {}) { _, _ -> }
  }
}
