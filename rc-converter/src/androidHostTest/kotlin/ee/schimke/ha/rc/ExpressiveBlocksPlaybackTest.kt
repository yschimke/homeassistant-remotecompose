@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.layout.RemoteCanvas
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.layout.RemoteSize
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemotePaint
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.rcplayer.compose.composeSupportReport
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcInput
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaButtonData
import ee.schimke.ha.rc.components.HaSliderSpec
import ee.schimke.ha.rc.components.HaToggleAccent
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ProvideHaUberTheme
import ee.schimke.ha.rc.components.RemoteHaSlider
import ee.schimke.ha.rc.components.RemoteHaToggleButton
import ee.schimke.rc.m3.LocalRcReduceMotion
import ee.schimke.rc.m3.RcAmbientValue
import ee.schimke.rc.m3.RcM3ProgressColors
import ee.schimke.rc.m3.RcMotionScheme
import ee.schimke.rc.m3.RemoteM3LoadingIndicator
import ee.schimke.rc.m3.RemoteM3WavyProgress
import ee.schimke.rc.m3.RemoteM3WavyProgressRing
import ee.schimke.rc.m3.animate
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
 * The M3 Expressive building blocks, played by the Compose player the app uses: a press squishes
 * the button's chip without taking its tap, and the wave and loader move on the player's clock (and
 * stay still when they aren't running). A frozen screenshot shows none of this, so every check here
 * compares frames.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpressiveBlocksPlaybackTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val sent = mutableListOf<HaAction?>()
  private val toggle = HaAction.Toggle("light.kitchen")

  private fun document(content: @Composable () -> Unit) = runBlocking {
    captureSingleRemoteDocument(
        context = compose.activity,
        creationDisplayInfo = RemoteCreationDisplayInfo(200, 200, 160),
        profile = androidXExperimentalWrap,
      ) {
        ProvideHaUberTheme(HaUberTheme.Expressive, darkTheme = false) { content() }
      }
      .bytes
  }

  /**
   * The test clock runs itself unless a test drives it: a loop on the player's clock never lets the
   * composition go idle, so those tests step the clock by hand.
   */
  private var manualClock = false

  private val values = mutableStateMapOf<String, RcNamedValue>()
  private val ambient = mutableStateOf<Boolean?>(false)

  private fun play(bytes: ByteArray) {
    val report =
      RcDocumentCodec.decode(bytes)
        .composeSupportReport(
          allowExternalImagePlaceholders = true,
          availableCustomComponents = RcInput.configs,
        )
    assertTrue(report.fullyRenderable, "the Compose player plays it: ${report.issues}")
    compose.mainClock.autoAdvance = !manualClock
    compose.setContent {
      Box(Modifier.size(200.dp, 200.dp).testTag("card")) {
        HaRcComposePlayer(
          documentBytes = bytes,
          modifier = Modifier.size(200.dp, 200.dp),
          namedValues = values,
          onNamedAction = { _, value -> sent.add(decodeHaAction(value?.toString().orEmpty())) },
          fallback = { error("plays in the Compose player") },
          ambient = ambient.value,
        )
      }
    }
    settle()
  }

  private fun settle(ms: Long = 800) {
    compose.mainClock.advanceTimeBy(ms)
    compose.waitForIdle()
  }

  private fun frame(): Bitmap = compose.onNodeWithTag("card").captureToImage().asAndroidBitmap()

  private fun Bitmap.differs(other: Bitmap): Int {
    var changed = 0
    for (y in 0 until height) for (x in 0 until width) {
      if (getPixel(x, y) != other.getPixel(x, y)) changed++
    }
    return changed
  }

  private val button =
    HaButtonData(
      entityId = "light.kitchen",
      name = "Kitchen",
      icon = Icons.Filled.Lightbulb,
      accent = HaToggleAccent(Color(0xFFFFA000).rc, Color(0xFF9E9E9E).rc),
      tapAction = toggle,
    )

  @Test
  fun pressSquishesTheChipAndTheTapStillSends() {
    play(document { RemoteHaToggleButton(button, RemoteModifier.fillMaxSize()) })
    val rest = frame()
    // Hold a press on the chip (the column's top half), then let go.
    compose.onRoot().performTouchInput { down(Offset(100f, 90f)) }
    settle(400)
    val held = frame()
    compose.onRoot().performTouchInput { up() }
    settle()
    val released = frame()
    assertTrue(
      held.differs(rest) > 20,
      "a held press reshapes the chip: held ${held.differs(rest)}, released " +
        "${released.differs(rest)}, sent $sent",
    )
    assertEquals(0, released.differs(rest), "releasing springs the chip back")
    assertEquals(toggle, sent.lastOrNull(), "the press doesn't take the tap: $sent")
  }

  @Test
  fun aRunningWaveMovesOnThePlayersClock() {
    manualClock = true
    play(
      document {
        RemoteM3WavyProgress(
          0.6f.rf,
          running = true,
          RcM3ProgressColors(Color.Blue.rc, Color.LightGray.rc),
          RemoteModifier.fillMaxSize(),
        )
      }
    )
    val first = frame()
    settle(250)
    assertTrue(frame().differs(first) > 20, "the wave travels")
  }

  @Test
  fun aStillRingStaysStill() {
    manualClock = true
    play(
      document {
        RemoteM3WavyProgressRing(
          0.6f.rf,
          running = false,
          RcM3ProgressColors(Color.Blue.rc, Color.LightGray.rc),
          RemoteModifier.fillMaxSize(),
        )
      }
    )
    val first = frame()
    settle(250)
    assertEquals(0, frame().differs(first), "a ring that isn't running doesn't move")
  }

  @Test
  fun aRunningRingMoves() {
    manualClock = true
    play(
      document {
        RemoteM3WavyProgressRing(
          0.6f.rf,
          running = true,
          RcM3ProgressColors(Color.Blue.rc, Color.LightGray.rc),
          RemoteModifier.fillMaxSize(),
        )
      }
    )
    val first = frame()
    settle(250)
    assertTrue(frame().differs(first) > 20, "the ring ripples")
  }

  @Test
  fun theLoaderMoves() {
    manualClock = true
    play(document { RemoteM3LoadingIndicator(Color.Blue.rc, RemoteModifier.fillMaxSize()) })
    val first = frame()
    settle(250)
    assertTrue(frame().differs(first) > 20, "the loader turns and morphs")
  }

  @Test
  fun reducedMotionStillsARunningRing() {
    manualClock = true
    play(
      document {
        CompositionLocalProvider(LocalRcReduceMotion provides true) {
          RemoteM3WavyProgressRing(
            0.6f.rf,
            running = true,
            RcM3ProgressColors(Color.Blue.rc, Color.LightGray.rc),
            RemoteModifier.fillMaxSize(),
          )
        }
      }
    )
    val first = frame()
    settle(250)
    assertEquals(0, frame().differs(first), "a viewer who asked for less motion sees no ripple")
  }

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

  private fun slider() = document {
    RemoteHaSlider(
      HaSliderSpec(brightness, "Brightness", unit = "%"),
      accent = Color(0xFFFFA000).rc,
      heightDp = 42,
      subject = "Kitchen",
    )
  }

  @Test
  fun expressiveSliderStillSendsWhereItWasDraggedTo() {
    play(slider())
    // 60 px of a 200 px slider is 30 of its 100 steps: 30 → 60.
    compose.onRoot().performTouchInput {
      down(Offset(60f, 20f))
      for (i in 1..8) moveTo(Offset(60f + 60f * i / 8, 20f), delayMillis = 16)
      moveTo(Offset(120f, 20f), delayMillis = 120)
      up()
    }
    settle()
    val release = sent.filterIsInstance<HaAction.SetValue>().last()
    assertEquals(60, release.ticks, "sent: $sent")
  }

  @Test
  fun holdingTheSliderNarrowsItsHandle() {
    play(slider())
    val rest = frame()
    compose.onRoot().performTouchInput { down(Offset(60f, 20f)) }
    settle(400)
    val held = frame()
    compose.onRoot().performTouchInput { up() }
    settle()
    val released = frame()
    assertTrue(held.differs(rest) > 10, "a held handle narrows: ${held.differs(rest)}")
    assertEquals(0, released.differs(rest), "the handle springs back")
  }

  /**
   * A bar as wide as the host's `test.level` (`0..1`), on the expressive scheme's spatial spring.
   */
  private fun bar() = document {
    val level = RemoteFloat.createNamedRemoteFloat("test.level", 0.2f, RemoteState.Domain.User)
    val shown = level.animate(RcMotionScheme.expressive().spatial)
    // Two animated values: the document still declares the ambient flag once.
    val alpha = level.animate(RcMotionScheme.expressive().effects)
    RemoteCanvas(RemoteModifier.fillMaxSize()) {
      drawRect(
        RemotePaint { style = PaintingStyle.Fill }
          .apply { color = Color.Blue.rc.copy(alpha = alpha) },
        RemoteOffset(0f.rf, 0f.rf),
        RemoteSize(width * shown, height),
      )
    }
  }

  /** The host's ambient state, changed on the UI thread as a host would. */
  private fun setAmbient(on: Boolean) = onUiThread { ambient.value = on }

  /** The host pushing a new `test.level`. */
  private fun setLevel(level: Float) = onUiThread {
    values["test.level"] = RcNamedValue.FloatValue(level)
  }

  private fun onUiThread(write: () -> Unit) = compose.runOnUiThread {
    write()
    Snapshot.sendApplyNotifications()
  }

  /** One frame's worth of the player's clock after a change. */
  private fun oneFrame() {
    compose.mainClock.advanceTimeByFrame()
    compose.waitForIdle()
  }

  @Test
  fun aDocumentReadsTheAmbientFlagOnce() {
    val declared = RcDocumentCodec.decode(bar()).declaredNamedVariables()
    assertEquals(
      1,
      declared.keys.count { it.endsWith(RcAmbientValue) },
      "one named ambient flag however many values animate: ${declared.keys}",
    )
  }

  @Test
  fun interactiveAChangeAnimates() {
    manualClock = true
    values["test.level"] = RcNamedValue.FloatValue(0.2f)
    play(bar())
    setLevel(0.9f)
    oneFrame()
    val moving = frame()
    settle(2_000)
    assertTrue(frame().differs(moving) > 20, "interactive, a change springs to its value")
  }

  @Test
  fun ambientAChangeLandsInTheNextFrame() {
    manualClock = true
    values["test.level"] = RcNamedValue.FloatValue(0.2f)
    setAmbient(true)
    play(bar())
    setLevel(0.9f)
    oneFrame()
    val first = frame()
    settle(2_000)
    // An ambient host draws about once a minute: the frame after a change is the one it keeps.
    assertEquals(0, frame().differs(first), "ambient, the first frame after a change is final")
  }

  @Test
  fun ambientStillsARunningWave() {
    manualClock = true
    setAmbient(true)
    play(
      document {
        RemoteM3WavyProgress(
          0.6f.rf,
          running = true,
          RcM3ProgressColors(Color.Blue.rc, Color.LightGray.rc),
          RemoteModifier.fillMaxSize(),
        )
      }
    )
    val first = frame()
    settle(250)
    assertEquals(0, frame().differs(first), "ambient, the wave rests as a flat line")
  }

  @Test
  fun goingAmbientTakesOneFrame() {
    manualClock = true
    play(slider())
    setAmbient(true)
    oneFrame()
    val first = frame()
    settle(1_000)
    assertEquals(0, frame().differs(first), "the first frame after the flag is the ambient look")
  }

  @Test
  fun ambientStillsTheLoader() {
    manualClock = true
    setAmbient(true)
    play(document { RemoteM3LoadingIndicator(Color.Blue.rc, RemoteModifier.fillMaxSize()) })
    val first = frame()
    settle(250)
    assertEquals(0, frame().differs(first), "ambient, the loader rests")
  }

  @Test
  fun leavingAmbientRestoresTheInteractiveLook() {
    manualClock = true
    val slider = slider()
    play(slider)
    val interactive = frame()
    setAmbient(true)
    settle(1_000)
    val ambientFrame = frame()
    assertTrue(ambientFrame.differs(interactive) > 20, "ambient thins the slider's tracks")
    setAmbient(false)
    settle(1_000)
    assertEquals(0, frame().differs(interactive), "and leaving ambient brings them back")
  }
}
