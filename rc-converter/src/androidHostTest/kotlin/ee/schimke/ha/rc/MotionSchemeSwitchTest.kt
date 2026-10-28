@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import androidx.compose.remote.core.Operation
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.core.operations.FloatExpression
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.runtime.CompositionLocalProvider
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.ProvideHaUberTheme
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
    runBlocking {
      val bytes =
        captureSingleRemoteDocument(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            creationDisplayInfo = RemoteCreationDisplayInfo(200, 100, 160),
            profile = androidXExperimentalWrap,
          ) {
            CompositionLocalProvider(LocalRcMotionMode provides mode) {
              ProvideHaUberTheme(theme, darkTheme = false) {
                RemoteHaToggleSwitch(
                  isOn = LiveValues.isOn("switch.kitchen", initial = false)!!,
                  activeAccent = currentRemoteHaTheme().linkText,
                  inactiveAccent = currentRemoteHaTheme().divider,
                )
              }
            }
          }
          .bytes
      val operations = arrayListOf<Operation>()
      RemoteComposeBuffer.fromInputStream(bytes.inputStream()).inflateFromBuffer(operations)
      operations
        .filterIsInstance<FloatExpression>()
        .mapNotNull { it.mSrcAnimation?.toList() }
        .toSet()
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
}
