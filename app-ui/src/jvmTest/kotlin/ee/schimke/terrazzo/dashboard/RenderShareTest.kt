package ee.schimke.terrazzo.dashboard

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import ee.schimke.terrazzo.shared.encodePng
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class RenderShareTest {
  @Test
  fun aWideDraftIsScaledDownAndSharedAsPng() {
    val drawn = ImageBitmap(2160, 900)
    Canvas(drawn).drawRect(0f, 0f, 2160f, 900f, Paint().apply { color = Color.Blue })
    val scaled = drawn.scaledTo(1080)
    assertEquals(1080 to 450, scaled.width to scaled.height)
    val png = assertNotNull(scaled.encodePng())
    assertContentEquals(
      byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()),
      png.copyOf(4),
    )
    // A narrow one is sent as drawn.
    val narrow = ImageBitmap(400, 300)
    assertSame(narrow, narrow.scaledTo(1080))
  }

  @Test
  fun aTallDraftIsBoundedInHeightToo() {
    val scaled = ImageBitmap(1080, 3600).scaledTo(1080, 1800)
    assertEquals(540 to 1800, scaled.width to scaled.height)
  }
}
