package ee.schimke.ha.rc.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

internal actual fun decodeBundledImage(bytes: ByteArray): ImageBitmap? = runCatching {
  val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
  BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}
  .getOrNull()
