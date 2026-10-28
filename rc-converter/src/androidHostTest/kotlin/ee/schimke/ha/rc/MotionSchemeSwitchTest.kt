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
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.ProvideHaUberTheme
import ee.schimke.ha.rc.components.RemoteHaGauge
import ee.schimke.ha.rc.components.RemoteHaToggleSwitch
import ee.schimke.ha.rc.components.currentRemoteHaTheme
import ee.schimke.rc.m3.LocalRcMotionMode
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
          CompositionLocalProvider(LocalRcMotionMode provides mode, content = content)
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
}
