package ee.schimke.terrazzo.shared

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream

actual fun ImageBitmap.encodePng(): ByteArray? = runCatching {
  val out = ByteArrayOutputStream()
  asAndroidBitmap()
    .copy(Bitmap.Config.ARGB_8888, false)
    .compress(Bitmap.CompressFormat.PNG, 100, out)
  out.toByteArray()
}
  .getOrNull()
