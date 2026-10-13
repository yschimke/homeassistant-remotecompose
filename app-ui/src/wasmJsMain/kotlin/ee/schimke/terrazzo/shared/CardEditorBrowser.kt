package ee.schimke.terrazzo.shared

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun CardEditorBrowser(
  url: String,
  modifier: Modifier,
  onNavigation: (String) -> Unit,
) {
  Text("Use Open in browser to continue in UI Builder.", modifier)
}
