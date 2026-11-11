package ee.schimke.ha.rc.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

internal actual fun encodeBitmapPng(bitmap: ImageBitmap): ByteArray =
  requireNotNull(Image.makeFromBitmap(bitmap.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG))
    .bytes

// The drag controls play in the AndroidX View player (docs/architecture/slider-controls.md), so
// only Android's captures write them: a desktop or web capture draws them read-only, as before.
internal actual val touchControlsSupported: Boolean = false
