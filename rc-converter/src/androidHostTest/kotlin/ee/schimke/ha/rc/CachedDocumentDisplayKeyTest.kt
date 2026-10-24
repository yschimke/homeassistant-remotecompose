package ee.schimke.ha.rc

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.haThemeFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A capture bakes the display's density and font scale, and the document cache outlives both: a
 * card must not replay bytes captured for another display (a font-size change, or a render run
 * previewing several devices in one process).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CachedDocumentDisplayKeyTest {

  @get:Rule val compose = createComposeRule()

  @Test
  fun anotherDensityOrFontScaleCapturesAgain() {
    val card =
      CardConfig(
        "entity",
        buildJsonObject {
          put("type", "entity")
          put("entity", "sensor.temp")
        },
      )
    val snapshot = HaSnapshot(states = mapOf("sensor.temp" to EntityState("sensor.temp", "21")))
    val registry = defaultRegistry()
    val theme = haThemeFor(ThemeStyle.TerrazzoHome, false)
    val keys = java.util.Collections.synchronizedList(mutableListOf<Any>())
    val cache =
      object : CardDocumentCache {
        val inner = InMemoryCardDocumentCache()

        override fun get(key: Any) = inner.get(key)

        override fun put(key: Any, document: CardDocument) {
          keys += key
          inner.put(key, document)
        }

        override fun clear() = inner.clear()
      }
    var density by mutableStateOf(Density(2f, 1f))

    compose.setContent {
      CompositionLocalProvider(
        LocalCardDocumentCache provides cache,
        LocalDensity provides density,
      ) {
        CachedCardPreview(cacheKey = "same-card", card = card, snapshot = snapshot) {
          ProvideCardRegistry(registry) {
            ProvideHaTheme(theme) { RenderChild(card, snapshot, RemoteModifier.fillMaxWidth()) }
          }
        }
      }
    }
    compose.waitForIdle()
    // A rebuild on screen captures off the main thread, the last document drawn meanwhile: wait for
    // each to land before the next change.
    density = Density(3f, 1f)
    compose.waitForIdle()
    compose.waitUntil(CAPTURE_TIMEOUT_MS) { keys.size == 2 }
    density = Density(3f, 1.3f)
    compose.waitForIdle()
    compose.waitUntil(CAPTURE_TIMEOUT_MS) { keys.size == 3 }
    density = Density(2f, 1f)
    compose.waitForIdle()

    // One capture per display; returning to the first replays its document.
    assertEquals(3, keys.size, "captures: $keys")
    assertEquals(3, keys.toSet().size)
  }

  private companion object {
    const val CAPTURE_TIMEOUT_MS = 10_000L
  }
}
