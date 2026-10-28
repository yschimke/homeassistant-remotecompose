@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.remote.player.view.RemoteComposePlayer
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.WidgetActionRegistry
import ee.schimke.ha.rc.components.decodeWidgetActionMetadata
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A tile with slider features, end to end: converted, captured and played by the AndroidX View
 * player, in the app (controls act) and as a launcher widget (controls locked until the strip is
 * slid). Each state is saved to `build/slider-captures/` for review, and the widget's sequence is
 * checked as a user would see it: a locked drag moves nothing and shows the strip, sliding the
 * strip unlocks, and a drag then sends the new brightness.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w600dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SliderVisualsTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val registry = defaultRegistry()

  private val light =
    EntityState(
      "light.kitchen",
      "on",
      JsonObject(
        mapOf(
          "friendly_name" to JsonPrimitive("Kitchen"),
          "brightness" to JsonPrimitive(158),
          "supported_color_modes" to JsonArray(listOf(JsonPrimitive("brightness"))),
        )
      ),
    )
  private val blind =
    EntityState(
      "cover.blind",
      "open",
      JsonObject(
        mapOf(
          "friendly_name" to JsonPrimitive("Blind"),
          "current_position" to JsonPrimitive(40),
          "supported_features" to JsonPrimitive(15),
        )
      ),
    )
  private val snapshot = HaSnapshot(states = listOf(light, blind).associateBy { it.entityId })

  private fun tile(entity: String, name: String, feature: String) =
    CardConfig(
      type = "tile",
      raw =
        buildJsonObject {
          put("type", "tile")
          put("entity", entity)
          put("name", name)
          putJsonArray("features") { addJsonObject { put("type", feature) } }
        },
    )

  private val brightnessTile = tile("light.kitchen", "Kitchen", "light-brightness")

  /** The widget is 4 × 2 cells, 288 × 180 dp, captured at xhdpi: 576 × 360 px. */
  private val widgetW = 576
  private val widgetH = 360

  private fun widget(card: CardConfig, actions: WidgetActionRegistry): ByteArray = runBlocking {
    captureSingleRemoteDocument(
        context = compose.activity,
        creationDisplayInfo = RemoteCreationDisplayInfo(widgetW, widgetH, 320),
        profile = widgetsProfile,
      ) {
        LauncherWidgetContent(
          registry,
          widgetsProfile,
          dark = false,
          actions,
          card,
          snapshot,
          captureSizeDp = WidgetCaptureSizeDp(288, 180),
        )
      }
      .bytes
  }

  /** The card as the app captures it ([captureCardDocument], the in-app profile). */
  private fun appCard(card: CardConfig, w: Int, h: Int): ByteArray = runBlocking {
    captureCardDocument(
        context = compose.activity,
        widthPx = w,
        heightPx = h,
        densityDpi = 320,
        registry = registry,
        card = card,
        snapshot = snapshot,
        forceRefresh = true,
        profile = androidXExperimentalWrap,
      )
      .bytes
  }

  private class Played(val player: RemoteComposePlayer, val w: Int, val h: Int) {
    val sent = mutableListOf<String>()
    private var down = 0L

    fun frame(): Bitmap {
      player.measure(
        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
      )
      player.layout(0, 0, w, h)
      val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
      player.draw(Canvas(image))
      return image
    }

    private fun event(action: Int, x: Float, y: Float) =
      MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)

    fun press(x: Float, y: Float) {
      down = SystemClock.uptimeMillis()
      player.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, x, y))
      frame()
    }

    fun moveTo(fromX: Float, toX: Float, y: Float) {
      for (i in 1..8) {
        player.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * i / 8, y))
        frame()
      }
    }

    fun release(x: Float, y: Float) {
      // Held still before lifting, as a deliberate slide is: the release has no fling in it.
      SystemClock.sleep(120)
      player.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, x, y))
      player.dispatchTouchEvent(event(MotionEvent.ACTION_UP, x, y))
      repeat(30) {
        SystemClock.sleep(16)
        frame()
      }
    }

    fun drag(fromX: Float, toX: Float, y: Float) {
      press(fromX, y)
      moveTo(fromX, toX, y)
      release(toX, y)
    }
  }

  private fun play(bytes: ByteArray, w: Int, h: Int, test: Played.() -> Unit) {
    compose.runOnIdle {
      val document =
        CoreDocument().apply {
          initFromBuffer(RemoteComposeBuffer.fromInputStream(bytes.inputStream()))
        }
      val player =
        RemoteComposePlayer(compose.activity).apply { setDocument(RemoteDocument(document)) }
      val played = Played(player, w, h)
      player.addIdActionListener { _, value -> played.sent.add(value.orEmpty()) }
      document.addActionCallback { _, value -> played.sent.add(value?.toString().orEmpty()) }
      played.frame()
      played.test()
    }
  }

  private fun save(name: String, image: Bitmap) {
    val dir = File("build/slider-captures").also { it.mkdirs() }
    dir.resolve("$name.png").outputStream().use {
      image.compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }

  private fun decode(value: String): HaAction? =
    decodeHaAction(decodeWidgetActionMetadata(value)?.actionPayload ?: value)

  /** The rows of [image] the slider's fill covers: its accent, at the track's left end. */
  private fun Bitmap.rowsOfColor(x: Int, color: Int): List<Int> =
    (0 until height).filter { getPixel(x, it) == color }

  @Test
  fun appTilesDragAtOnce() {
    val w = 720
    val h = 300
    play(appCard(brightnessTile, w, h), w, h) {
      val rest = frame()
      save("app-brightness", rest)
      // The slider is the feature row under the name: find it by its fill at the track's start.
      val sliderY = findSliderRow(rest)
      press(200f, sliderY.toFloat())
      moveTo(200f, 320f, sliderY.toFloat())
      save("app-brightness-dragging", frame())
      release(320f, sliderY.toFloat())
      val sent = sent.mapNotNull(::decode).filterIsInstance<HaAction.SetValue>().last()
      assertTrue(sent.ticks > sent.from, "dragging right raises the brightness: $sent")
    }
  }

  @Test
  fun appCoverPosition() {
    play(appCard(tile("cover.blind", "Blind", "cover-position"), 720, 300), 720, 300) {
      save("app-cover-position", frame())
    }
  }

  /**
   * The middle row of the slider track: the longest run of one opaque colour, other than the card's
   * own, down the track's filled start (the icon above it is shorter).
   */
  private fun findSliderRow(image: Bitmap): Int {
    val x = 30
    val card = image.getPixel(x, 4)
    var best = 0 to 0
    var start = 0
    for (y in 1..image.height) {
      val run = y == image.height || image.getPixel(x, y) != image.getPixel(x, start)
      if (run) {
        val colour = image.getPixel(x, start)
        val opaque = colour ushr 24 == 0xFF
        if (opaque && colour != card && y - start > best.second - best.first) best = start to y
        start = y
      }
    }
    return (best.first + best.second) / 2
  }

  @Test
  fun aWidgetUnlocksBySlidingTheStrip() {
    val actions = WidgetActionRegistry(mapOf("light.kitchen" to "62 %"))
    play(widget(brightnessTile, actions), widgetW, widgetH) {
      val locked = frame()
      save("widget-1-locked", locked)
      val sliderY = findSliderRow(locked).toFloat()

      // A drag on the locked slider: nothing moves, and the unlock strip appears.
      drag(200f, 400f, sliderY)
      val hint = frame()
      save("widget-2-touched-shows-strip", hint)
      val first = sent.mapNotNull(::decode).filterIsInstance<HaAction.SetValue>().last()
      assertTrue(first.isUnchanged, "a locked slider doesn't move: $first")

      // Slide the strip's knob: the strip runs across the widget's bottom, 8 dp in.
      val stripY = widgetH - 16f - 48f
      press(40f, stripY)
      moveTo(40f, widgetW / 2f, stripY)
      save("widget-3-unlocking", frame())
      moveTo(widgetW / 2f, widgetW - 20f, stripY)
      release(widgetW - 20f, stripY)
      save("widget-4-unlocked", frame())

      sent.clear()
      press(200f, sliderY)
      moveTo(200f, 400f, sliderY)
      save("widget-5-dragging", frame())
      release(400f, sliderY)
      save("widget-6-released", frame())
      val moved = sent.mapNotNull(::decode).filterIsInstance<HaAction.SetValue>().last()
      assertTrue(moved.ticks > moved.from, "unlocked, the drag raises the brightness: $moved")
      assertEquals("brightness_pct", moved.field)
    }
  }
}
