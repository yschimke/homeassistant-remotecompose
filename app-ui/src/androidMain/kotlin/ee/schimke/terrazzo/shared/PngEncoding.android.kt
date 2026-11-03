package ee.schimke.terrazzo.shared

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream

actual fun ImageBitmap.encodePng(): ByteArray? = runCatching {
  val out = ByteArrayOutputStream()
  asAndroidBitmap()
    .copy(Bitmap.Config.ARGB_8888, false)
    .compress(Bitmap.CompressFormat.PNG, 100, out)
  out.toByteArray()
}
  .getOrNull()

actual fun ImageBitmap.drawable(): ImageBitmap {
  val bitmap = asAndroidBitmap()
  if (bitmap.config != Bitmap.Config.HARDWARE) return this
  return bitmap.copy(Bitmap.Config.ARGB_8888, false).asImageBitmap()
}
