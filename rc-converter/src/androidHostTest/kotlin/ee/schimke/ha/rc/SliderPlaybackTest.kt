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
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.profile.Profile
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.remote.player.view.RemoteComposePlayer
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaControlLockMode
import ee.schimke.ha.rc.components.HaSliderSpec
import ee.schimke.ha.rc.components.ProvideHaControlLockMode
import ee.schimke.ha.rc.components.ProvideWidgetActionRegistry
import ee.schimke.ha.rc.components.RemoteHaControlLockFrame
import ee.schimke.ha.rc.components.RemoteHaSlider
import ee.schimke.ha.rc.components.WidgetActionRegistry
import ee.schimke.ha.rc.components.decodeWidgetActionMetadata
import ee.schimke.ha.rc.components.toRemoteAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A slider and the control lock, played by the AndroidX View player: the player a launcher widget
 * runs (the platform's copy of it), and the one every in-app card with a slider falls back to (the
 * Compose player has no touch expression outside a scroll, see `HaRcComposePlayer`).
 *
 * Each test drives real `MotionEvent`s through the View and reads what it sends and draws. The
 * document is 300 × 160 px at mdpi: a 42 px slider (0–100 in steps of 1, resting at 30) above a 40
 * px toggle button, and, while locked, the unlock strip across the bottom (y 104–152).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w600dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SliderPlaybackTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val brightness =
    HaAction.SetValue(
      domain = "light",
      service = "turn_on",
      entityId = "light.kitchen",
      field = "brightness_pct",
      min = 0.0,
      step = 1.0,
      maxTicks = 100,
      from = 30,
      ticks = 30,
    )

  private val toggle = HaAction.Toggle("light.kitchen")

  @Composable
  private fun Card() {
    RemoteBox(modifier = RemoteModifier.fillMaxSize()) {
      RemoteHaControlLockFrame {
        RemoteColumn(modifier = RemoteModifier.fillMaxSize()) {
          RemoteHaSlider(
            HaSliderSpec(brightness, "Brightness", unit = "%"),
            accent = Color(0xFFFFA000).rc,
            heightDp = 42,
            subject = "Kitchen",
          )
          val tap = toggle.toRemoteAction()
          RemoteBox(
            modifier =
              RemoteModifier.fillMaxWidth()
                .height(40.rdp)
                .then(if (tap != null) RemoteModifier.clickable(tap) else RemoteModifier)
          )
        }
      }
    }
  }

  private fun document(mode: HaControlLockMode, registry: WidgetActionRegistry?, profile: Profile) =
    runBlocking {
      captureSingleRemoteDocument(
          context = compose.activity,
          creationDisplayInfo = RemoteCreationDisplayInfo(300, 160, 160),
          profile = profile,
        ) {
          ProvideHaControlLockMode(mode) {
            if (registry != null) ProvideWidgetActionRegistry(registry) { Card() } else Card()
          }
        }
        .bytes
    }

  /** A View player on [bytes], laid out at the document's size, recording what it sends. */
  private class Played(val player: RemoteComposePlayer) {
    val sent = mutableListOf<String>()

    fun frame(): Bitmap {
      // A visibility change lays the document out again: measure and lay out as a host would.
      player.measure(
        View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY),
      )
      player.layout(0, 0, 300, 160)
      val image = Bitmap.createBitmap(300, 160, Bitmap.Config.ARGB_8888)
      player.draw(Canvas(image))
      return image
    }

    fun drag(fromX: Float, toX: Float, y: Float) {
      val down = SystemClock.uptimeMillis()
      fun event(action: Int, x: Float) =
        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
      player.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, fromX))
      frame()
      val steps = 8
      for (i in 1..steps) {
        player.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * i / steps))
        frame()
      }
      // Held still before lifting, as a deliberate slide is: the release has no fling in it.
      SystemClock.sleep(120)
      player.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, toX))
      player.dispatchTouchEvent(event(MotionEvent.ACTION_UP, toX))
      // The knob eases to its end after release, on the player's wall clock, which Robolectric's
      // SystemClock doesn't move: let real time pass too, or a fast machine draws all 30 frames
      // before the ease is done.
      repeat(30) {
        SystemClock.sleep(16)
        Thread.sleep(16)
        frame()
      }
    }

    fun tap(x: Float, y: Float) = drag(x, x, y)
  }

  private fun play(bytes: ByteArray, test: Played.() -> Unit) {
    compose.runOnIdle {
      val document =
        CoreDocument().apply {
          initFromBuffer(RemoteComposeBuffer.fromInputStream(bytes.inputStream()))
        }
      val player =
        RemoteComposePlayer(compose.activity).apply { setDocument(RemoteDocument(document)) }
      val played = Played(player)
      // Named actions (the in-app `ha` taps) reach the document's own callbacks.
      document.addActionCallback { _, value -> played.sent.add(value?.toString().orEmpty()) }
      player.addIdActionListener { _, value -> played.sent.add(value.orEmpty()) }
      player.measure(
        View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY),
      )
      player.layout(0, 0, 300, 160)
      played.frame()
      played.test()
    }
  }

  /** The [HaAction] a sent value carries: a widget's metadata envelope, or a named payload. */
  private fun decode(value: String): HaAction? =
    decodeHaAction(decodeWidgetActionMetadata(value)?.actionPayload ?: value)

  private fun Played.lastSetValue(): HaAction.SetValue =
    sent.mapNotNull(::decode).filterIsInstance<HaAction.SetValue>().last()

  @Test
  fun unlockedSliderSendsWhereItWasDraggedTo() {
    play(document(HaControlLockMode.Off, null, androidXExperimentalWrap)) {
      // 60 px of a 300 px slider is 20 of its 100 steps: 30 → 50.
      drag(100f, 160f, 20f)
      val sent = lastSetValue()
      assertEquals(50, sent.ticks)
      assertEquals(30, sent.from)
      assertEquals(
        kotlinx.serialization.json.JsonPrimitive(50L),
        sent.toCallService()?.serviceData?.get("brightness_pct"),
      )
    }
  }

  @Test
  fun aTapSendsTheUnchangedValue() {
    play(document(HaControlLockMode.Off, null, androidXExperimentalWrap)) {
      tap(100f, 20f)
      val sent = lastSetValue()
      assertTrue(sent.isUnchanged, "a tap leaves the slider where it was: $sent")
      assertEquals(null, sent.toCallService())
    }
  }

  @Test
  fun lockedWidgetHoldsControlsUntilSlidOpen() {
    val registry = WidgetActionRegistry()
    play(document(HaControlLockMode.Locked, registry, widgetsProfile)) {
      val stripPixel = { frame().getPixel(150, 128) }
      val before = stripPixel()

      // Locked: a drag doesn't move the slider, and the toggle sends nothing runnable.
      drag(100f, 160f, 20f)
      assertTrue(lastSetValue().isUnchanged, "a locked slider doesn't move: ${sent.last()}")
      tap(150f, 60f)
      assertEquals(HaAction.None, decode(sent.last()), "a locked toggle sends None")
      println(
        "STRIP before=${Integer.toHexString(before)} after=${Integer.toHexString(stripPixel())} row=" +
          (0 until 300 step 20).map { Integer.toHexString(frame().getPixel(it, 128)) }
      )
      assertTrue(stripPixel() != before, "the unlock strip shows after a locked touch")

      // The toggle's own payload is marked locked: a host refuses it without the metadata.
      val toggleId = registry.entries.entries.single { decodeHaAction(it.value) == toggle }.key
      assertTrue(toggleId in registry.lockedEntries, "the locked toggle is marked for the host")

      // Slide the knob across the strip.
      drag(30f, 295f, 128f)
      sent.clear()

      drag(100f, 160f, 20f)
      assertEquals(50, lastSetValue().ticks, "unlocked, the slider moves: $sent")
      tap(150f, 60f)
      assertEquals(toggle, decode(sent.last()), "unlocked, the toggle sends its action")
    }
  }

  @Test
  fun lockedInAppHoldsControlsUntilSlidOpen() {
    play(document(HaControlLockMode.Locked, null, androidXExperimentalWrap)) {
      val stripPixel = { frame().getPixel(150, 128) }
      val before = stripPixel()

      // Locked: a drag doesn't move the slider, and the toggle sends nothing runnable.
      drag(100f, 160f, 20f)
      assertTrue(lastSetValue().isUnchanged, "a locked slider doesn't move: ${sent.last()}")
      tap(150f, 60f)
      assertEquals(HaAction.None, decode(sent.last()), "a locked toggle sends None")
      println(
        "STRIP before=${Integer.toHexString(before)} after=${Integer.toHexString(stripPixel())} row=" +
          (0 until 300 step 20).map { Integer.toHexString(frame().getPixel(it, 128)) }
      )
      assertTrue(stripPixel() != before, "the unlock strip shows after a locked touch")

      // Slide the knob across the strip.
      drag(30f, 295f, 128f)
      sent.clear()

      drag(100f, 160f, 20f)
      assertEquals(50, lastSetValue().ticks, "unlocked, the slider moves: $sent")
      tap(150f, 60f)
      assertEquals(toggle, decode(sent.last()), "unlocked, the toggle sends its action")
    }
  }
}
