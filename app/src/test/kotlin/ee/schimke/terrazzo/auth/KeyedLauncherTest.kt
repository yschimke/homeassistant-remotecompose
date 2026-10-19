package ee.schimke.terrazzo.auth

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sign-in leaves the app for a Custom Tab; the system may recreate the Activity before the tab
 * returns. These tests replay that order against a registry we control: launch, the UI goes away
 * (its saved state kept), the result lands while nothing is registered, then the UI comes back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class KeyedLauncherTest {
  @get:Rule val compose = createComposeRule()

  private val registry = RecordingRegistry()
  private val received = mutableListOf<Pair<String, Int>>()
  private var shown by mutableStateOf(true)
  private var launch: ((String, Intent) -> Unit)? = null

  private fun showLauncher() {
    val owner =
      object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = registry
      }
    compose.setContent {
      val savedState = rememberSaveableStateHolder()
      CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
        if (shown) {
          savedState.SaveableStateProvider("login") {
            launch = rememberKeyedLauncher { key, result -> received += key to result.resultCode }
          }
        }
      }
    }
  }

  @Test
  fun resultAfterRecreationReachesTheHandlerWithItsKey() {
    showLauncher()
    compose.runOnIdle { launch!!("https://ha.example", Intent()) }

    compose.runOnIdle { shown = false }
    compose.runOnIdle {
      assertTrue(registry.dispatchResult(registry.launched.single(), Activity.RESULT_OK, Intent()))
    }
    compose.runOnIdle { shown = true }

    compose.runOnIdle { assertEquals(listOf("https://ha.example" to Activity.RESULT_OK), received) }
  }

  @Test
  fun resultWithoutRecreationIsDeliveredOnce() {
    showLauncher()
    compose.runOnIdle { launch!!("https://ha.example", Intent()) }
    compose.runOnIdle {
      registry.dispatchResult(registry.launched.single(), Activity.RESULT_CANCELED, null)
    }

    compose.runOnIdle {
      assertEquals(listOf("https://ha.example" to Activity.RESULT_CANCELED), received)
    }
  }

  private class RecordingRegistry : ActivityResultRegistry() {
    val launched = mutableListOf<Int>()

    override fun <I, O> onLaunch(
      requestCode: Int,
      contract: ActivityResultContract<I, O>,
      input: I,
      options: ActivityOptionsCompat?,
    ) {
      launched += requestCode
    }
  }
}
