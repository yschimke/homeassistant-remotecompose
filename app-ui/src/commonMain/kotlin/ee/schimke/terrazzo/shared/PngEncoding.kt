package ee.schimke.terrazzo.shared

import androidx.compose.ui.graphics.ImageBitmap

/** This image as PNG bytes, or null where it can't be encoded. */
expect fun ImageBitmap.encodePng(): ByteArray?

/**
 * This image in memory a software canvas can draw from. A layer capture on Android is a hardware
 * bitmap, which a software canvas rejects; elsewhere it already is one.
 */
expect fun ImageBitmap.drawable(): ImageBitmap
