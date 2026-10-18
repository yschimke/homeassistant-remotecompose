package ee.schimke.terrazzo

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.onConsumedWindowInsetsChanged
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.shared.CardStudioScreen
import ee.schimke.terrazzo.ui.AndroidImeHost
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidEdgeToEdgeTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun cardStudioConsumesScaffoldInsetsBeforeEmbeddingPreview() {
    var consumed: WindowInsets? = null
    var bars: WindowInsets? = null
    var density: Density? = null
    compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
    compose.setContent {
      density = LocalDensity.current
      bars = WindowInsets.safeDrawing
      MaterialTheme {
        AndroidImeHost {
          CardStudioScreen(
            card = CardConfig("tile", buildJsonObject {}),
            snapshot = HaSnapshot(),
            onBack = {},
            preview = {
              Box(
                Modifier.fillMaxWidth().height(32.dp).onConsumedWindowInsetsChanged {
                  consumed = it
                }
              )
            },
          )
        }
      }
    }
    compose.waitForIdle()
    compose.runOnIdle {
      val d = checkNotNull(density)
      val systemBars = checkNotNull(bars)
      val applied = checkNotNull(consumed)
      assertTrue("Test needs a visible status bar", systemBars.getTop(d) > 0)
      assertTrue(
        "Preview must not apply status bar padding twice",
        applied.getTop(d) >= systemBars.getTop(d),
      )
      assertTrue(
        "Preview must consume left cutout/system inset",
        applied.getLeft(d, LayoutDirection.Ltr) >= systemBars.getLeft(d, LayoutDirection.Ltr),
      )
      assertTrue(
        "Preview must consume right cutout/system inset",
        applied.getRight(d, LayoutDirection.Ltr) >= systemBars.getRight(d, LayoutDirection.Ltr),
      )
      assertTrue(
        "Preview must not apply navigation bar padding twice",
        applied.getBottom(d) >= systemBars.getBottom(d),
      )
    }
  }
}
