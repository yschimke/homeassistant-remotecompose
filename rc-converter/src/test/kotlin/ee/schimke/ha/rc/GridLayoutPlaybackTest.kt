package ee.schimke.ha.rc

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.haThemeFor
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `grid` columns and picture `aspect_ratio` at playback: one captured document, played through the
 * default (Compose) card player at different widths, lays its cells out per row by the width it is
 * given and sizes square / aspect cells from their live width. Heights are the observable result: a
 * row of squares is as tall as one cell is wide.
 *
 * Renders land in `build/grid-layout-playback/` for review. Known player issue, not the grid's: a
 * `RemoteSizeBreakpoint` tier first revealed by a resize draws its icon chips without their round
 * clip in the Compose card player (it reproduces with a single tile behind a breakpoint, no grid),
 * so the second width of a test that crosses the grid's gate shows square chips.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h2000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GridLayoutPlaybackTest {

  @get:Rule val compose = createComposeRule()

  private val registry = defaultRegistry()

  private val snapshot =
    HaSnapshot(
      states =
        listOf(
            entity("camera.chamber", "streaming", "friendly_name" to "Chamber"),
            entity("camera.nozzle", "streaming", "friendly_name" to "Nozzle"),
            entity("light.kitchen", "on", "friendly_name" to "Kitchen"),
            entity("light.office", "off", "friendly_name" to "Office"),
            entity("switch.fan", "on", "friendly_name" to "Fan"),
            entity("switch.pump", "off", "friendly_name" to "Pump"),
          )
          .associateBy { it.entityId }
    )

  private fun entity(id: String, state: String, vararg attrs: Pair<String, String>) =
    EntityState(id, state, JsonObject(attrs.associate { (k, v) -> k to JsonPrimitive(v) }))

  private fun card(json: String): CardConfig {
    val raw = Json.parseToJsonElement(json).jsonObject
    return CardConfig(type = (raw["type"] as JsonPrimitive).content, raw = raw)
  }

  private val squarePictures =
    card(
      """{"type":"grid","columns":2,"cards":[
        {"type":"picture-entity","entity":"camera.chamber","aspect_ratio":"1:1"},
        {"type":"picture-entity","entity":"camera.nozzle","aspect_ratio":"1:1"}]}"""
    )

  private val fourButtons =
    card(
      """{"type":"grid","columns":4,"cards":[
        {"type":"button","entity":"light.kitchen"},{"type":"button","entity":"light.office"},
        {"type":"button","entity":"switch.fan"},{"type":"button","entity":"switch.pump"}]}"""
    )

  private val sixTiles =
    card(
      """{"type":"grid","columns":3,"cards":[
        {"type":"tile","entity":"light.kitchen"},{"type":"tile","entity":"light.office"},
        {"type":"tile","entity":"switch.fan"},{"type":"tile","entity":"switch.pump"},
        {"type":"tile","entity":"light.kitchen"},{"type":"tile","entity":"switch.fan"}]}"""
    )

  /**
   * Plays [card]'s one document at each of [widthsDp] in turn; returns each played height in dp.
   */
  private fun heightsAt(name: String, card: CardConfig, vararg widthsDp: Int): List<Float> {
    val theme = haThemeFor(ThemeStyle.TerrazzoHome, darkTheme = true)
    var width by mutableIntStateOf(widthsDp.first())
    compose.setContent {
      Box(Modifier.width(width.dp).testTag("card")) {
        CachedCardPreview(
          // One key: every width plays the same captured document.
          cacheKey = "grid-playback-$name",
          profile = androidXExperimentalWrap,
          card = card,
          snapshot = snapshot,
        ) {
          ProvideCardRegistry(registry) {
            ProvideHaTheme(theme) { RenderChild(card, snapshot, RemoteModifier.fillMaxWidth()) }
          }
        }
      }
    }
    return widthsDp.map { w ->
      compose.runOnIdle { width = w }
      compose.waitForIdle()
      played[w] = save("$name-${w}dp")
      compose.onNodeWithTag("card").getUnclippedBoundsInRoot().let { it.bottom - it.top }.value
    }
  }

  /** The last frame played at each width, by width in dp. */
  private val played = mutableMapOf<Int, PixelMap>()

  private fun save(name: String): PixelMap {
    val image = compose.onNodeWithTag("card").captureToImage()
    val dir = File("build/grid-layout-playback").also { it.mkdirs() }
    dir.resolve("$name.png").outputStream().use {
      image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
    return image.toPixelMap()
  }

  /** The window behind the card: every gap between cells shows it. */
  private fun isBackground(c: Color) = (c.red + c.green + c.blue) / 3f > 0.8f

  /**
   * The row of [perRow] equal cells played at [widthDp], sampled across its middle at [yDp]: each
   * cell sits where `(width − gaps) ÷ perRow` puts it, with an empty gap between neighbours, so no
   * cell is wider than its share and none runs past the right edge.
   */
  private fun assertCellsFit(widthDp: Int, perRow: Int, yDp: Float, what: String) {
    val pixels = played.getValue(widthDp)
    val scale = pixels.width / widthDp.toFloat()
    val y = (yDp * scale).toInt()
    val cell = (widthDp - 8f * (perRow - 1)) / perRow
    for (k in 0 until perRow) {
      val start = k * (cell + 8f)
      // The cell itself has content across its middle.
      val mid = ((start + cell / 2f) * scale).toInt()
      assertTrue(!isBackground(pixels[mid, y]), "$what: cell $k should have content at x=$mid")
      if (k > 0) {
        // The gap before it is empty: a cell sized width ÷ perRow (gaps ignored) covers it.
        val gap = start - 8f
        for (x in ((gap + 2f) * scale).toInt()..((gap + 6f) * scale).toInt()) {
          assertTrue(
            isBackground(pixels[x, y]),
            "$what: gap before cell $k should be empty at x=$x (cells overflow the row)",
          )
        }
      }
    }
  }

  private fun assertNear(expected: Float, actual: Float, what: String) =
    assertTrue(abs(expected - actual) <= 2f, "$what: expected ≈ $expected dp, was $actual dp")

  @Test
  fun twoSquarePicturesShareARowAndGrowWithTheWidth() {
    val (phone, tablet) = heightsAt("square-pictures", squarePictures, 360, 700)
    // Two squares side by side: one row as tall as a cell is wide.
    assertNear((360f - 8f) / 2f, phone, "360 dp")
    assertNear((700f - 8f) / 2f, tablet, "700 dp")
    assertCellsFit(360, perRow = 2, yDp = phone / 2f, "pictures at 360 dp")
    assertCellsFit(700, perRow = 2, yDp = tablet / 2f, "pictures at 700 dp")
  }

  @Test
  fun twoSquarePicturesStackWhenTooNarrowForTwo() {
    // 2 × 140 + 8 = 288 dp is the narrowest two-across row; at 250 dp each picture takes a row.
    val (narrow) = heightsAt("square-pictures-narrow", squarePictures, 250)
    assertNear(250f * 2f + 8f, narrow, "250 dp")
  }

  @Test
  fun fourColumnButtonsFitAPhoneRowAsSquares() {
    val (phone, narrow) = heightsAt("four-buttons", fourButtons, 360, 300)
    assertNear((360f - 3 * 8f) / 4f, phone, "360 dp, one row of four")
    // 300 dp is narrower than four 80 dp cells: three to a row, then one.
    val cell = (300f - 2 * 8f) / 3f
    assertNear(cell * 2 + 8f, narrow, "300 dp, three and one")
    assertCellsFit(360, perRow = 4, yDp = phone / 2f, "buttons at 360 dp")
    assertCellsFit(300, perRow = 3, yDp = cell / 2f, "buttons at 300 dp")
  }

  @Test
  fun fourColumnButtonsAtATabletWidth() {
    val (tablet) = heightsAt("four-buttons-700", fourButtons, 700)
    assertNear((700f - 3 * 8f) / 4f, tablet, "700 dp, one row of four")
    assertCellsFit(700, perRow = 4, yDp = tablet / 2f, "buttons at 700 dp")
  }

  @Test
  fun tileGridWrapsToMoreRowsOnAPhone() {
    val (phone, tablet) = heightsAt("tiles", sixTiles, 360, 700)
    // Tiles keep their own height; a phone lays out 2 per row (3 rows), a tablet 3 (2 rows).
    val rowWithGap = (phone - tablet) // one extra row and its gap
    assertTrue(rowWithGap > 30f, "phone ($phone dp) should be one row taller than tablet ($tablet)")
    assertNear(tablet * 1.5f + 4f, phone, "three rows vs two")
    assertCellsFit(360, perRow = 2, yDp = 24f, "tiles at 360 dp")
    assertCellsFit(700, perRow = 3, yDp = 24f, "tiles at 700 dp")
  }

  @Test
  fun pictureEntityHonoursAspectRatio() {
    val wide = card("""{"type":"picture-entity","entity":"camera.chamber","aspect_ratio":"16:9"}""")
    val (h360, h700) = heightsAt("picture-16x9", wide, 360, 700)
    assertNear(360f * 9f / 16f, h360, "16:9 at 360 dp")
    assertNear(700f * 9f / 16f, h700, "16:9 at 700 dp")
  }

  @Test
  fun aPercentageAspectRatioIsHeightOverWidth() {
    val half = card("""{"type":"picture-entity","entity":"camera.chamber","aspect_ratio":"50%"}""")
    val (h) = heightsAt("picture-50pc", half, 360)
    assertNear(180f, h, "50% at 360 dp")
  }
}
