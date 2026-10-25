package ee.schimke.terrazzo

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import ee.schimke.terrazzo.core.session.DemoHaSession
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.core.session.SessionConnectionStatus
import java.io.File
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MobileConnectionStatusUiTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun pendingConnectionUsesConnectingChromeWithoutAnError() {
    val raw = MutableStateFlow(SessionConnectionStatus.Failed)
    val session =
      object : HaSession by DemoHaSession() {
        override val connectionStatus = raw
      }
    compose.setContent { StatusChrome(rememberMobileConnectionStatus(session)) }
    compose.onNodeWithContentDescription("Connecting").assertExists()
    compose.onNodeWithText("Can't reach Home Assistant").assertDoesNotExist()
    screenshot("after-startup-pending")

    compose.runOnIdle { raw.value = SessionConnectionStatus.Connected }
    compose.onNodeWithContentDescription("Connected").assertExists()
    compose.onNodeWithText("Can't reach Home Assistant").assertDoesNotExist()
    screenshot("after-recovery")
  }

  @Test
  fun firstFramesUseCurrentTargetAndForegroundState() {
    val raw = MutableStateFlow(SessionConnectionStatus.Failed)
    val failed =
      object : HaSession by DemoHaSession() {
        override val connectionStatus = raw
      }
    var session by mutableStateOf<HaSession>(failed)
    val owner =
      object : LifecycleOwner {
        override val lifecycle =
          LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
      }
    val frames = mutableListOf<ConnectionStatus>()
    compose.setContent {
      CompositionLocalProvider(LocalLifecycleOwner provides owner) {
        val status = rememberMobileConnectionStatus(session)
        SideEffect { frames += status }
        StatusChrome(status)
      }
    }
    compose.onNodeWithContentDescription("Connecting").assertExists()
    compose.runOnIdle { assertEquals(ConnectionStatus.Connecting, frames.first()) }
    compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.STARTED }
    compose.onNodeWithContentDescription("Paused").assertExists()
    compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
    compose.onNodeWithContentDescription("Connecting").assertExists()
    compose.runOnIdle { session = DemoHaSession() }
    compose.onNodeWithContentDescription("Connected").assertExists()
    compose.runOnIdle { session = failed }
    compose.onNodeWithContentDescription("Connecting").assertExists()
  }

  @Test
  fun confirmedFailureKeepsExistingErrorChrome() {
    // The same raw startup failure used to be shown immediately. Keep its appearance for an
    // outage that survives the grace period, and capture it for the before/after comparison.
    compose.setContent { StatusChrome(ConnectionStatus.Failed) }
    compose.onNodeWithContentDescription("Failed").assertExists()
    compose.onNodeWithText("Can't reach Home Assistant").assertExists()
    screenshot("before-immediate-error")
  }

  @Composable
  private fun StatusChrome(status: ConnectionStatus) {
    MaterialTheme {
      Surface {
        Column(Modifier.fillMaxWidth()) {
          Text("Home Assistant", style = MaterialTheme.typography.titleLarge)
          ConnectionStatusDot(status = status, onClick = {})
          ConnectionStatusStrip(status = status, onRetry = {})
        }
      }
    }
  }

  private fun screenshot(name: String) {
    val directory = File("build/reports/mobile-connection-status").apply { mkdirs() }
    compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
      require(bitmap.width <= 1800 && bitmap.height <= 1800)
      File(directory, "$name.png").outputStream().use {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
      }
    }
  }
}
