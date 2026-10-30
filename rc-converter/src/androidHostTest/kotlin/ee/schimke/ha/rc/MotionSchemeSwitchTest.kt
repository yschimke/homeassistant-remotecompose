@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import androidx.compose.remote.core.Operation
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.core.operations.FloatExpression
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.CompositionLocalProvider
import ee.schimke.ha.rc.components.HaGaugeData
import ee.schimke.ha.rc.components.HaMotion
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.ProvideHaUberTheme
import ee.schimke.ha.rc.components.RemoteHaGauge
import ee.schimke.ha.rc.components.RemoteHaToggleSwitch
import ee.schimke.ha.rc.components.currentRemoteHaTheme
import ee.schimke.rc.m3.LocalRcMotionMode
import ee.schimke.rc.m3.LocalRcReduceMotion
import ee.schimke.rc.m3.LocalRcSpringsSupported
import ee.schimke.rc.m3.RcMotionMode
import ee.schimke.rc.m3.RcMotionScheme
import ee.schimke.rc.m3.RcMotionSpec
import ee.schimke.rc.m3.encode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The switch takes its motion from the card style's scheme: the animation descriptions in the
 * encoded document are the scheme's tokens, as the player will read them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MotionSchemeSwitchTest {
  private fun animations(theme: HaUberTheme, mode: RcMotionMode = RcMotionMode.Normal) =
    animationsOf(mode) {
      ProvideHaUberTheme(theme, darkTheme = false) {
        RemoteHaToggleSwitch(
          isOn = LiveValues.isOn("switch.kitchen", initial = false)!!,
          activeAccent = currentRemoteHaTheme().linkText,
          inactiveAccent = currentRemoteHaTheme().divider,
        )
      }
    }

  private fun animationsOf(
    mode: RcMotionMode = RcMotionMode.Normal,
    content: @androidx.compose.runtime.Composable () -> Unit,
  ) = runBlocking {
    val bytes =
      captureSingleRemoteDocument(
          context = org.robolectric.RuntimeEnvironment.getApplication(),
          creationDisplayInfo = RemoteCreationDisplayInfo(200, 100, 160),
          profile = androidXExperimentalWrap,
        ) {
          // The motion a part's own state change runs; the ambient look's fade is a document's
          // too, and ExpressiveBlocksPlaybackTest plays it.
          CompositionLocalProvider(
            LocalRcMotionMode provides mode,
            ee.schimke.rc.m3.LocalRcAmbientAware provides false,
            content = content,
          )
        }
        .bytes
    val operations = arrayListOf<Operation>()
    RemoteComposeBuffer.fromInputStream(bytes.inputStream()).inflateFromBuffer(operations)
    operations.filterIsInstance<FloatExpression>().mapNotNull { it.mSrcAnimation?.toList() }.toSet()
  }

  private fun RcMotionSpec.wire() = encode()!!.toList()

  @Test
  fun classicKeepsOneTweenForKnobAndColour() {
    assertEquals(setOf(listOf(0.20f)), animations(HaUberTheme.Classic))
  }

  @Test
  fun expressiveSpringsTheKnobAndFadesTheColour() {
    val scheme = RcMotionScheme.expressive()
    assertEquals(
      setOf(scheme.spatialFast.wire(), scheme.effectsFast.wire()),
      animations(HaUberTheme.Expressive),
    )
  }

  @Test
  fun ambientModeOverridesTheStyle() {
    val ambient = RcMotionScheme.ambient()
    assertEquals(
      setOf(ambient.spatialFast.wire(), ambient.effectsFast.wire()),
      animations(HaUberTheme.Expressive, RcMotionMode.Ambient),
    )
  }

  @Test
  fun reducedMotionSnapsTheKnob() {
    assertEquals(
      setOf(RcMotionScheme.expressive().effectsFast.wire()),
      animations(HaUberTheme.Expressive, RcMotionMode.Reduced),
    )
  }

  @Test
  fun paletteOnlyKeepsTheClassicTween() {
    val animations = animationsOf {
      ProvideHaTheme(HaTheme.Light) {
        RemoteHaToggleSwitch(
          isOn = LiveValues.isOn("switch.kitchen", initial = false)!!,
          activeAccent = currentRemoteHaTheme().linkText,
          inactiveAccent = currentRemoteHaTheme().divider,
        )
      }
    }
    assertEquals(setOf(listOf(0.20f)), animations)
  }

  @Test
  fun ambientGaugeSpringsItsFraction() {
    val gauge =
      @androidx.compose.runtime.Composable {
        RemoteHaGauge(
          HaGaugeData(
            entityId = "sensor.humidity",
            name = "Humidity",
            valueText = "40".rs,
            unit = "%",
            value = LiveValues.numericState("sensor.humidity", 40f),
            min = 0.0,
            max = 100.0,
          )
        )
      }
    assertEquals(setOf(listOf(0.45f)), animationsOf(content = gauge))
    assertEquals(
      setOf(RcMotionScheme.ambient().spatial.wire()),
      animationsOf(RcMotionMode.Ambient, gauge),
    )
  }

  @Test
  fun consoleLampWarmsFastAndCoolsSlowly() {
    val instrument = HaMotion.Instrument
    assertEquals(
      setOf(
        instrument.spatialFast.wire(),
        instrument.effectsFast.wire(),
        instrument.effectsSlow.wire(),
      ),
      animations(HaUberTheme.Console),
    )
  }

  @Test
  fun lcdSnapsTheKnobAndFadesTheSegments() {
    assertEquals(setOf(HaMotion.Lcd.effectsFast.wire()), animations(HaUberTheme.Lcd))
  }

  @Test
  fun everyStyleTakesItsFamilysArchetype() {
    assertEquals(HaMotion.Classic, HaUberTheme.Classic.motion)
    assertEquals(RcMotionScheme.expressive(), HaUberTheme.Expressive.motion)
    assertEquals(RcMotionScheme.standard(), HaUberTheme.HomeAssistant.motion)
    for (theme in listOf(HaUberTheme.Console, HaUberTheme.Trinitron, HaUberTheme.Braun)) {
      assertEquals(HaMotion.Instrument, theme.motion, theme.id)
    }
    assertEquals(HaMotion.Lcd, HaUberTheme.Lcars.motion)
    assertEquals(HaMotion.Nixie, HaUberTheme.Nixie.motion)
    assertEquals(HaMotion.Cupertino, HaUberTheme.Cupertino.motion)
    assertEquals(RcMotionScheme.standard(), HaUberTheme.Mushroom.motion)
  }

  @Test
  fun reduceMotionAppliesOverAmbient() {
    val animations =
      animationsOf(RcMotionMode.Ambient) {
        CompositionLocalProvider(LocalRcReduceMotion provides true) {
          ProvideHaUberTheme(HaUberTheme.Expressive, darkTheme = false) {
            RemoteHaToggleSwitch(
              isOn = LiveValues.isOn("switch.kitchen", initial = false)!!,
              activeAccent = currentRemoteHaTheme().linkText,
              inactiveAccent = currentRemoteHaTheme().divider,
            )
          }
        }
      }
    assertEquals(setOf(RcMotionScheme.ambient().effectsFast.wire()), animations)
  }

  @Test
  fun unsupportedSpringsEncodeAsTweens() {
    val animations = animationsOf {
      CompositionLocalProvider(LocalRcSpringsSupported provides false) {
        ProvideHaUberTheme(HaUberTheme.Expressive, darkTheme = false) {
          RemoteHaToggleSwitch(
            isOn = LiveValues.isOn("switch.kitchen", initial = false)!!,
            activeAccent = currentRemoteHaTheme().linkText,
            inactiveAccent = currentRemoteHaTheme().divider,
          )
        }
      }
    }
    val expressive = RcMotionScheme.expressive()
    assertEquals(
      setOf(
        (expressive.spatialFast as RcMotionSpec.Spring).asTween().wire(),
        (expressive.effectsFast as RcMotionSpec.Spring).asTween().wire(),
      ),
      animations,
    )
  }
}
