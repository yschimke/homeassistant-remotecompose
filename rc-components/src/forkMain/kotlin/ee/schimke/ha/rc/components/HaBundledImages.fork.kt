package ee.schimke.ha.rc.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

// Skia decodes on every fork platform (desktop, browser, iOS). A host without Skia's native library
// (the design-library generator on a plain JVM) gets null, and the card falls back to text.
internal actual fun decodeBundledImage(bytes: ByteArray): ImageBitmap? = runCatching {
  Image.makeFromEncoded(bytes).toComposeImageBitmap()
}
  .getOrNull()
