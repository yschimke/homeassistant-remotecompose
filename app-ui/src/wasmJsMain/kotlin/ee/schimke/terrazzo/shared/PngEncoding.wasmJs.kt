package ee.schimke.terrazzo.shared

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

actual fun ImageBitmap.encodePng(): ByteArray? = runCatching {
  Image.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes
}
  .getOrNull()

actual fun ImageBitmap.drawable(): ImageBitmap = this
