package ee.schimke.ha.rc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.haThemeFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A host's display state reaches its cards: [LocalHostAmbient] above [CachedCardPreview] writes the
 * ambient flag into the cached document as it plays, so a light theme's card takes the ambient
 * palette while the host is ambient and its own when it isn't, with no re-encode
 * (docs/architecture/ambient-mode.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HostAmbientTest {

  @get:Rule val compose = createComposeRule()

  @Test
  fun aCardFollowsItsHostIntoAmbientAndBack() {
    val raw = Json.parseToJsonElement("""{"type":"entity","entity":"$SENSOR"}""").jsonObject
    val card = CardConfig("entity", raw)
    val snapshot =
      HaSnapshot(
        mapOf(
          SENSOR to
            EntityState(
              SENSOR,
              "21.4",
              JsonObject(mapOf("friendly_name" to JsonPrimitive("Living Room"))),
            )
        )
      )
    val registry = defaultRegistry()
    val theme = haThemeFor(ThemeStyle.TerrazzoHome, false)
    val ambient = mutableStateOf<Boolean?>(false)
    compose.setContent {
      CompositionLocalProvider(
        LocalCardDocumentCache provides InMemoryCardDocumentCache(),
        LocalHostAmbient provides ambient.value,
      ) {
        Box(Modifier.background(Color.Magenta).width(381.dp).testTag("card")) {
          CachedCardPreview(
            cacheKey = "host-ambient",
            profile = androidXExperimentalWrap,
            card = card,
            snapshot = snapshot,
          ) {
            ProvideCardRegistry(registry) {
              ProvideHaTheme(theme) { RenderChild(card, snapshot, RemoteModifier.fillMaxWidth()) }
            }
          }
        }
      }
    }
    compose.waitForIdle()
    // The card's text, by its light pixels: a light theme prints dark text, the ambient palette
    // light grey. (An entity card draws no surface of its own: its text is on the host's backdrop.)
    val lightText = {
      val frame = compose.onNodeWithTag("card").captureToImage().asAndroidBitmap()
      var count = 0
      for (y in 0 until frame.height) for (x in 0 until frame.width) {
        val pixel = frame.getPixel(x, y)
        if (pixel != android.graphics.Color.MAGENTA && Color(pixel).luminance() > 0.6f) count++
      }
      count
    }
    val interactive = lightText()
    assertEquals(0, interactive, "a light theme's text is dark")

    compose.runOnIdle { ambient.value = true }
    compose.waitForIdle()
    assertTrue(lightText() > 50, "ambient, the text is light grey: ${lightText()} light pixels")

    compose.runOnIdle { ambient.value = false }
    compose.waitForIdle()
    assertEquals(interactive, lightText(), "and back")
  }

  private companion object {
    const val SENSOR = "sensor.living_room"
  }
}
