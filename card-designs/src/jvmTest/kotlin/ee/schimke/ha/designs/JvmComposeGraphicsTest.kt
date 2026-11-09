package ee.schimke.ha.designs

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The generator bakes cards on a plain JVM, where no Compose scene has started. Card code may still
 * draw an [ImageBitmap] there, as it does in the app and previews (#1464).
 */
class JvmComposeGraphicsTest {
  @Test
  fun cardCodeCanDrawAnImageBitmapBeforeAnySceneStarts() {
    JvmComposeGraphics.ensureRegistered()
    JvmComposeGraphics.ensureRegistered() // Once per process, however often it's asked.
    val tile = ImageBitmap(7, 7)
    Canvas(tile).drawCircle(Offset(3.5f, 3.5f), 2f, Paint().apply { color = Color.White })
    assertEquals(7, tile.width)
  }
}
