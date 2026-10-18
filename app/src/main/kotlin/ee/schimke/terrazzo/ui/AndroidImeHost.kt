package ee.schimke.terrazzo.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Give every Android screen a viewport above the keyboard. Consuming the IME here lets descendant
 * scaffolds handle the remaining system bars without adding the navigation bar a second time.
 * Scroll containers (including those hosting Remote Compose players) receive the reduced height, so
 * their last fields and actions remain reachable. With the IME closed, screens keep ownership of
 * their normal edge-to-edge system-bar insets.
 */
@Composable
internal fun AndroidImeHost(content: @Composable () -> Unit) {
  Box(Modifier.fillMaxSize()) { content() }
}
