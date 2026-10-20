package ee.schimke.terrazzo.shared

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.Test
import kotlinx.serialization.json.Json

@OptIn(ExperimentalTestApi::class)
class LibraryCardFallbackTest {
  @Test
  fun missingDesignIsLabeledAndFallbackTracksSelectedStyle() = runComposeUiTest {
    var style by mutableStateOf("lcd")
    val card = Json.decodeFromString<CardConfig>("""{"type":"gauge","entity":"sensor.battery"}""")
    setContent {
      val scope = rememberCoroutineScope()
      val cache = remember { DashboardLoadCache<LibraryDesignKey, ByteArray?>(scope) { null } }
      MaterialTheme {
        LibraryCard(card, HaSnapshot(), style, false, designCache = cache) {
          Text("fallback:${MaterialTheme.colorScheme.surface.toArgb()}")
        }
      }
    }
    waitUntil(timeoutMillis = 5_000) {
      onAllNodesWithText("Design unavailable · showing built-in card")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    onNodeWithText("fallback:${0xFFB9C3A2.toInt()}").assertExists()
    runOnIdle { style = "braun" }
    onNodeWithText("fallback:${0xFFF8F6F0.toInt()}").assertExists()
    onNodeWithText("fallback:${0xFFB9C3A2.toInt()}").assertDoesNotExist()
  }
}
