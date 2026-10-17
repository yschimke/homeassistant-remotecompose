package ee.schimke.ha.rc

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.haThemeFor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What an entities card row ends in, as HA's rows do: a sensor that can't be switched reads its
 * state ("Open", "Detected") rather than drawing a switch, and a switchable entity that is
 * unavailable reads "Unavailable" in place of its switch. The render is saved to
 * `build/entity-row-captures/` for review.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EntityRowTrailingCaptureTest {

  @get:Rule val compose = createComposeRule()

  private val registry = defaultRegistry()

  private fun entity(id: String, state: String, name: String, deviceClass: String? = null) =
    EntityState(
      id,
      state,
      JsonObject(
        buildMap {
          put("friendly_name", JsonPrimitive(name))
          deviceClass?.let { put("device_class", JsonPrimitive(it)) }
        }
      ),
    )

  private val snapshot =
    HaSnapshot(
      states =
        listOf(
            entity("binary_sensor.front_door", "on", "Front door", "door"),
            entity("binary_sensor.hall_motion", "on", "Hall motion", "motion"),
            entity("light.porch", "unavailable", "Porch light"),
            entity("light.hall", "on", "Hall light"),
          )
          .associateBy { it.entityId }
    )

  private val card =
    CardConfig(
      type = "entities",
      raw =
        buildJsonObject {
          put("type", "entities")
          putJsonArray("entities") { snapshot.states.keys.forEach { add(JsonPrimitive(it)) } }
        },
    )

  @Test
  fun sensorsReadTheirStateAndAnUnavailableSwitchSaysSo() {
    val theme = haThemeFor(ThemeStyle.TerrazzoHome, false)
    compose.setContent {
      Box(Modifier.background(Color.White).width(360.dp).testTag("card")) {
        CachedCardPreview(
          cacheKey = "entity-rows",
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
    compose.waitForIdle()
    save("entity-rows")

    for (text in listOf("Open", "Detected", "Unavailable")) {
      assertEquals(
        1,
        compose.onAllNodesWithText(text).fetchSemanticsNodes().size,
        "a row reads \"$text\"",
      )
    }
    // The available light keeps its switch: no "On" text.
    assertEquals(0, compose.onAllNodesWithText("On").fetchSemanticsNodes().size)
  }

  private fun save(name: String) {
    val image = compose.onNodeWithTag("card").captureToImage()
    val dir = File("build/entity-row-captures").also { it.mkdirs() }
    dir.resolve("$name.png").outputStream().use {
      image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }
}
