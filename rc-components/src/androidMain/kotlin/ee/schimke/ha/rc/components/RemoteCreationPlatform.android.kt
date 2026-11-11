package ee.schimke.ha.rc.components

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream

internal actual fun encodeBitmapPng(bitmap: ImageBitmap): ByteArray =
  ByteArrayOutputStream()
    .also { bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    .toByteArray()

internal actual val touchControlsSupported: Boolean = true
