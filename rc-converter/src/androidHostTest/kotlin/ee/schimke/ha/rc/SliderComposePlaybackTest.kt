@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.rcplayer.compose.composeSupportReport
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcInput
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaControlLockMode
import ee.schimke.ha.rc.components.HaSliderSpec
import ee.schimke.ha.rc.components.ProvideHaControlLockMode
import ee.schimke.ha.rc.components.RemoteHaControlLockFrame
import ee.schimke.ha.rc.components.RemoteHaSlider
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
 * The in-app half of [SliderPlaybackTest]: the same slider card, played by the Compose
 * Multiplatform player through [HaRcComposePlayer] the way the app and the watch play it. Nothing
 * here may fall back to the AndroidX View player — the fallback fails the test.
 *
 * The card is 300 × 160 px at mdpi: a 42 px slider (0–100 in steps of 1, resting at 30) above a 40
 * px toggle button, and, while locked, the unlock strip across the bottom (y 104–152).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w600dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SliderComposePlaybackTest {
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

  private fun document(mode: HaControlLockMode) = runBlocking {
    captureSingleRemoteDocument(
        context = compose.activity,
        creationDisplayInfo = RemoteCreationDisplayInfo(300, 160, 160),
        profile = androidXExperimentalWrap,
      ) {
        ProvideHaControlLockMode(mode) { Card() }
      }
      .bytes
  }

  private val sent = mutableListOf<HaAction?>()

  private fun play(bytes: ByteArray) {
    val report =
      RcDocumentCodec.decode(bytes)
        .composeSupportReport(
          allowExternalImagePlaceholders = true,
          availableCustomComponents = RcInput.configs,
        )
    assertTrue(report.fullyRenderable, "the Compose player plays a slider card: ${report.issues}")
    compose.setContent {
      Box(Modifier.size(300.dp, 160.dp)) {
        HaRcComposePlayer(
          documentBytes = bytes,
          modifier = Modifier.size(300.dp, 160.dp),
          onNamedAction = { _, value -> sent.add(decodeHaAction(value?.toString().orEmpty())) },
          fallback = { error("a slider card plays in the Compose player") },
        )
      }
    }
    compose.waitForIdle()
  }

  private fun drag(fromX: Float, toX: Float, y: Float) {
    compose.onRoot().performTouchInput {
      down(Offset(fromX, y))
      val steps = 8
      for (i in 1..steps) moveTo(Offset(fromX + (toX - fromX) * i / steps, y), delayMillis = 16)
      // Held still before lifting, as a deliberate slide is.
      moveTo(Offset(toX, y), delayMillis = 120)
      up()
    }
    // The knob eases to its end after release; let the frame clock run.
    compose.mainClock.advanceTimeBy(600)
    compose.waitForIdle()
  }

  private fun tap(x: Float, y: Float) = drag(x, x, y)

  private fun lastSetValue(): HaAction.SetValue = sent.filterIsInstance<HaAction.SetValue>().last()

  @Test
  fun unlockedSliderSendsWhereItWasDraggedTo() {
    play(document(HaControlLockMode.Off))
    // 60 px of a 300 px slider is 20 of its 100 steps: 30 → 50.
    drag(100f, 160f, 20f)
    val release = lastSetValue()
    assertEquals(50, release.ticks, "sent: $sent")
    assertEquals(30, release.from)
  }

  @Test
  fun aTapSendsTheUnchangedValue() {
    play(document(HaControlLockMode.Off))
    tap(100f, 20f)
    assertTrue(lastSetValue().isUnchanged, "a tap leaves the slider where it was: $sent")
  }

  @Test
  fun lockedCardHoldsControlsUntilSlidOpen() {
    play(document(HaControlLockMode.Locked))

    // Locked: a drag doesn't move the slider, and the toggle sends nothing runnable.
    drag(100f, 160f, 20f)
    assertTrue(lastSetValue().isUnchanged, "a locked slider doesn't move: $sent")
    tap(150f, 60f)
    assertEquals(HaAction.None, sent.last(), "a locked toggle sends None")

    // Slide the knob across the strip the touch showed.
    drag(30f, 295f, 128f)
    sent.clear()

    drag(100f, 160f, 20f)
    assertEquals(50, lastSetValue().ticks, "unlocked, the slider moves: $sent")
    tap(150f, 60f)
    assertEquals(toggle, sent.last(), "unlocked, the toggle sends its action")
  }
}
