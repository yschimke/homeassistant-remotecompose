package ee.schimke.ha.rc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
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
 * An entity's name is read once: the icon beside a drawn name is decorative, not a second label
 * ("Hall light, Hall light, On"), including the on/off glyph pair a light swaps between. An
 * entities card row is a full 48dp touch target.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EntityNameSpokenOnceTest {

  @get:Rule val compose = createComposeRule()

  private fun entity(id: String, state: String, name: String) =
    EntityState(id, state, JsonObject(mapOf("friendly_name" to JsonPrimitive(name))))

  private val snapshot =
    HaSnapshot(
      states =
        listOf(entity("light.hall", "on", "Hall light"), entity("sensor.status", "Idle", "Status"))
          .associateBy { it.entityId }
    )

  private fun render(card: CardConfig) {
    val registry = defaultRegistry()
    val theme = haThemeFor(ThemeStyle.TerrazzoHome, false)
    compose.setContent {
      Box(Modifier.background(Color.White).width(360.dp)) {
        CachedCardPreview(
          cacheKey = "name-once-${card.type}",
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
  }

  private fun assertNamedOnce(name: String) {
    assertEquals(1, compose.onAllNodesWithText(name).fetchSemanticsNodes().size, "\"$name\" drawn")
    assertEquals(
      0,
      compose.onAllNodesWithContentDescription(name).fetchSemanticsNodes().size,
      "no icon also labelled \"$name\"",
    )
  }

  @Test
  fun entitiesRowsReadTheNameOnceAndAreFullTouchTargets() {
    render(
      CardConfig(
        "entities",
        buildJsonObject {
          put("type", "entities")
          putJsonArray("entities") {
            add(JsonPrimitive("light.hall"))
            add(JsonPrimitive("sensor.status"))
          }
        },
      )
    )
    assertNamedOnce("Hall light")
    assertNamedOnce("Status")

    val density = compose.onRoot().fetchSemanticsNode().layoutInfo.density.density
    val rows =
      compose.onRoot().fetchSemanticsNode().let { root ->
        generateSequence(listOf(root)) { level -> level.flatMap { it.children }.ifEmpty { null } }
          .flatten()
          .filter { it.config.getOrNull(SemanticsActions.OnClick) != null }
          .filter { it.size.width / density > 300f }
          .toList()
      }
    assertEquals(2, rows.size, "two full-width tappable rows")
    for (row in rows) {
      val heightDp = row.size.height / density
      assertTrue(heightDp >= 48f, "a row is a 48dp touch target, was ${heightDp}dp")
    }
  }

  @Test
  fun aTileReadsTheNameOnce() {
    render(
      CardConfig(
        "tile",
        buildJsonObject {
          put("type", "tile")
          put("entity", "light.hall")
        },
      )
    )
    assertNamedOnce("Hall light")
  }
}
