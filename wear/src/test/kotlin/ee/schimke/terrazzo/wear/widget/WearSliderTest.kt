@file:Suppress("RestrictedApi")

package ee.schimke.terrazzo.wear.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.remote.player.view.RemoteComposePlayer
import androidx.wear.compose.remote.material3.RemoteColorScheme
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.androidXExperimentalWrap
import ee.schimke.ha.rc.cardHasDragControls
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaControlLockMode
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.decodeHaAction
import ee.schimke.terrazzo.wear.ui.terrazzoWearColorScheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A tile with a brightness slider on the watch, in each of its three places: a Wear widget (the
 * slider shows its value and doesn't drag: every widget tap opens the app), the Wear app's list
 * (locked behind "Slide to unlock") and the full-screen card (drags at once). Played by the
 * AndroidX View player the app falls back to for a slider card; each is saved to
 * `build/wear-sliders/`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WearSliderTest {
  private val context = RuntimeEnvironment.getApplication()
  private val registry = defaultRegistry()
  private val colors = RemoteColorScheme(terrazzoWearColorScheme(ThemeStyle.entries.first()))

  private val card =
    CardConfig(
      type = "tile",
      raw =
        buildJsonObject {
          put("type", "tile")
          put("entity", "light.kitchen")
          putJsonArray("features") { addJsonObject { put("type", "light-brightness") } }
        },
    )

  private val snapshot =
    HaSnapshot(
      states =
        mapOf(
          "light.kitchen" to
            EntityState(
              "light.kitchen",
              "on",
              buildJsonObject {
                put("friendly_name", "Kitchen")
                put("brightness", 128)
                put("supported_color_modes", JsonArray(listOf(JsonPrimitive("brightness"))))
              },
            )
        )
    )

  private val w = 200
  private val h = 112

  /**
   * [SlotContent] in [mode], captured as the Wear app captures a card it has no template for
   * (`CachedCardPreview`, the in-app profile) at the Wear Large container's canvas.
   */
  private fun capture(mode: HaControlLockMode): ByteArray = runBlocking {
    captureSingleRemoteDocument(
        context = context,
        creationDisplayInfo = RemoteCreationDisplayInfo(w, h, 160),
        profile = androidXExperimentalWrap,
      ) {
        SlotContent(registry, colors, card, snapshot, template = false, HaUberTheme.Classic, mode)
      }
      .bytes
  }

  private class Played(val player: RemoteComposePlayer, val w: Int, val h: Int) {
    val sent = mutableListOf<String>()

    fun frame(): Bitmap {
      player.measure(
        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
      )
      player.layout(0, 0, w, h)
      return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { player.draw(Canvas(it)) }
    }

    fun drag(fromX: Float, toX: Float, y: Float) {
      val down = SystemClock.uptimeMillis()
      fun event(action: Int, x: Float) =
        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
      player.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, fromX))
      frame()
      for (i in 1..8) {
        player.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * i / 8))
        frame()
      }
      // Held still before lifting, as a deliberate slide is: the release has no fling in it.
      SystemClock.sleep(120)
      player.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, toX))
      player.dispatchTouchEvent(event(MotionEvent.ACTION_UP, toX))
      // The knob eases on the player's wall clock, which Robolectric's SystemClock doesn't move:
      // let real time pass too, or a fast machine draws all 30 frames before the ease is done.
      repeat(30) {
        SystemClock.sleep(16)
        Thread.sleep(16)
        frame()
      }
    }
  }

  private fun hasTouchExpression(bytes: ByteArray): Boolean {
    val operations = arrayListOf<androidx.compose.remote.core.Operation>()
    RemoteComposeBuffer.fromInputStream(bytes.inputStream()).inflateFromBuffer(operations)
    return operations.any { it is androidx.compose.remote.core.operations.TouchExpression }
  }

  private fun play(bytes: ByteArray, test: Played.(ByteArray) -> Unit) {
    val document =
      CoreDocument().apply {
        initFromBuffer(RemoteComposeBuffer.fromInputStream(bytes.inputStream()))
      }
    val player = RemoteComposePlayer(context).apply { setDocument(RemoteDocument(document)) }
    val played = Played(player, w, h)
    player.addIdActionListener { _, value -> played.sent.add(value.orEmpty()) }
    document.addActionCallback { _, value -> played.sent.add(value?.toString().orEmpty()) }
    played.frame()
    played.test(bytes)
  }

  private fun save(name: String, image: Bitmap) {
    val dir = File("build/wear-sliders").also { it.mkdirs() }
    // Three times the watch's mdpi pixels, for review.
    val big = Bitmap.createScaledBitmap(image, image.width * 3, image.height * 3, true)
    dir.resolve("$name.png").outputStream().use { big.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }

  private fun Played.setValues(): List<HaAction.SetValue> =
    sent.mapNotNull(::decodeHaAction).filterIsInstance<HaAction.SetValue>()

  /** The slider sits under the compact tile: its middle row. */
  private val sliderY = 62f

  @Test
  fun aTileWithASliderIsADragCard() {
    assertTrue(cardHasDragControls(card))
  }

  @Test
  fun theWidgetShowsTheValueWithoutADrag() {
    play(capture(HaControlLockMode.ReadOnly)) { bytes ->
      save("widget-read-only", frame())
      assertFalse(hasTouchExpression(bytes), "a Wear widget's slider carries no touch expression")
      drag(40f, 160f, sliderY)
      assertEquals(emptyList(), setValues())
    }
  }

  @Test
  fun theListLocksTheSliderUntilSlidOpen() {
    play(capture(HaControlLockMode.Locked)) {
      save("list-1-locked", frame())
      drag(40f, 160f, sliderY)
      assertTrue(setValues().all { it.isUnchanged }, "a locked slider doesn't move: $sent")
      save("list-2-touched-shows-strip", frame())
      // The strip runs across the card's bottom, 8 dp in: slide its knob across.
      drag(20f, 196f, h - 8f - 24f)
      save("list-3-unlocked", frame())
      sent.clear()
      drag(40f, 160f, sliderY)
      save("list-4-moved", frame())
      assertTrue(setValues().last().ticks > setValues().last().from, "unlocked it moves: $sent")
    }
  }

  @Test
  fun theFullScreenCardDragsAtOnce() {
    play(capture(HaControlLockMode.Off)) {
      drag(40f, 160f, sliderY)
      save("full-screen-moved", frame())
      val moved = setValues().last()
      assertTrue(moved.ticks > moved.from, "full screen, the drag sends the new value: $sent")
    }
  }
}
