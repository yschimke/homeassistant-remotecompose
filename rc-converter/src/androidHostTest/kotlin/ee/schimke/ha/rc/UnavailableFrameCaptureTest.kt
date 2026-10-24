package ee.schimke.ha.rc

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ProvideHaUberTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A card about one unavailable entity shows it the way its uber theme does, the same for every card
 * type: the content faded and the theme's label over it ("Unavailable", Braun's quiet
 * "unavailable", the LCD's "----"). An available entity's card has no label. Each theme's sheet is
 * saved to `build/unavailable-frame/` for review.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h1400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UnavailableFrameCaptureTest {

  @get:Rule val compose = createComposeRule()

  private fun entity(id: String, state: String, vararg attrs: Pair<String, String>) =
    EntityState(id, state, JsonObject(attrs.associate { (k, v) -> k to JsonPrimitive(v) }))

  private val snapshot =
    HaSnapshot(
      states =
        listOf(
            entity("light.porch", "unavailable", "friendly_name" to "Porch"),
            entity(
              "sensor.garage_temp",
              "unavailable",
              "friendly_name" to "Garage",
              "unit_of_measurement" to "°C",
            ),
            entity("light.hall", "on", "friendly_name" to "Hall"),
          )
          .associateBy { it.entityId }
    )

  private fun card(type: String, entity: String) =
    CardConfig(
      type,
      buildJsonObject {
        put("type", type)
        put("entity", entity)
      },
    )

  private val unavailableCards =
    listOf(
      card("tile", "light.porch"),
      card("button", "light.porch"),
      card("entity", "sensor.garage_temp"),
      card("gauge", "sensor.garage_temp"),
    )

  private fun renderSheet(theme: HaUberTheme, dark: Boolean, name: String) {
    val registry = defaultRegistry()
    val cards = unavailableCards + card("tile", "light.hall")
    compose.setContent {
      Column(
        Modifier.background(if (dark) Color.Black else Color(0xFFEEEEEE))
          .padding(8.dp)
          .testTag("sheet"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        cards.chunked(3).forEach { row ->
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { c ->
              Box(Modifier.width(240.dp)) {
                CachedCardPreview(
                  cacheKey = "unavailable-$name-${c.type}-${c.raw}",
                  profile = androidXExperimentalWrap,
                  card = c,
                  snapshot = snapshot,
                ) {
                  ProvideCardRegistry(registry) {
                    ProvideHaUberTheme(theme, dark) {
                      RenderChild(c, snapshot, RemoteModifier.fillMaxWidth())
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
    compose.waitForIdle()
    val image = compose.onNodeWithTag("sheet").captureToImage()
    val dir = File("build/unavailable-frame").also { it.mkdirs() }
    dir.resolve("$name.png").outputStream().use {
      image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }

  /**
   * Classic's label is the word a card may also print as its state, so this counts at least one per
   * unavailable card; the themes below, whose labels differ, count exactly.
   */
  @Test
  fun classicLabelsEachUnavailableCard() {
    renderSheet(HaUberTheme.Classic, dark = false, "classic")
    val labels = compose.onAllNodesWithText("Unavailable").fetchSemanticsNodes().size
    assertTrue(labels >= unavailableCards.size, "a label per unavailable card, was $labels")
  }

  @Test fun braunUsesItsOwnLabel() = assertLabel(HaUberTheme.Braun, "unavailable")

  @Test fun lcdShowsDashes() = assertLabel(HaUberTheme.Lcd, "----")

  private fun assertLabel(theme: HaUberTheme, label: String) {
    renderSheet(theme, dark = false, theme.id)
    assertEquals(
      unavailableCards.size,
      compose.onAllNodesWithText(label).fetchSemanticsNodes().size,
      "one \"$label\" per unavailable card, none on the available tile",
    )
  }

  /** A stack has no entity of its own, so each child shows its own entity's unavailability. */
  @Test
  fun aStacksUnavailableChildIsFramed() {
    val stack =
      CardConfig(
        "vertical-stack",
        buildJsonObject {
          put("type", "vertical-stack")
          put(
            "cards",
            kotlinx.serialization.json.JsonArray(
              listOf(card("tile", "light.porch").raw, card("tile", "light.hall").raw)
            ),
          )
        },
      )
    val registry = defaultRegistry()
    compose.setContent {
      Box(Modifier.width(240.dp)) {
        CachedCardPreview(
          cacheKey = "unavailable-stack",
          profile = androidXExperimentalWrap,
          card = stack,
          snapshot = snapshot,
        ) {
          ProvideCardRegistry(registry) {
            ProvideHaUberTheme(HaUberTheme.Lcd, false) {
              RenderChild(stack, snapshot, RemoteModifier.fillMaxWidth())
            }
          }
        }
      }
    }
    compose.waitForIdle()
    assertEquals(1, compose.onAllNodesWithText("----").fetchSemanticsNodes().size)
  }
}
