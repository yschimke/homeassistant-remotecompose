package ee.schimke.terrazzo.shared

import androidx.compose.ui.graphics.ImageBitmap

/** This image as PNG bytes, or null where it can't be encoded. */
expect fun ImageBitmap.encodePng(): ByteArray?
