package ee.schimke.ha.rc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every tappable part of a card is at least a 48dp touch target in both directions — media
 * transport buttons, a picture glance's entity icons, a to-do item — while the part drawn inside it
 * keeps its own size. These are the cards the accessibility check flagged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardTouchTargetTest {

  @get:Rule val compose = createComposeRule()

  private fun state(id: String, state: String, vararg attrs: Pair<String, JsonElement>) =
    EntityState(id, state, JsonObject(attrs.toMap()))

  private fun name(name: String) = "friendly_name" to JsonPrimitive(name)

  private val snapshot =
    HaSnapshot(
      states =
        listOf(
            state(
              "media_player.office_speaker",
              "playing",
              name("Office speaker"),
              "media_title" to JsonPrimitive("The Anthem"),
              "media_artist" to JsonPrimitive("Good Charlotte"),
              "media_position" to JsonPrimitive(75.0),
              "media_duration" to JsonPrimitive(176.0),
            ),
            state(
              "todo.shopping",
              "2",
              name("Shopping list"),
              "items" to
                JsonArray(
                  listOf("Salt" to "needs_action", "Milk" to "completed").map { (s, st) ->
                    JsonObject(mapOf("summary" to JsonPrimitive(s), "status" to JsonPrimitive(st)))
                  }
                ),
            ),
            state("light.living_room_lamp", "on", name("Lamp")),
            state("light.kitchen_lights", "off", name("Kitchen")),
            state(
              "cover.living_room_blinds",
              "open",
              name("Blinds"),
              "device_class" to JsonPrimitive("blind"),
            ),
            state("switch.fan", "on", name("Fan")),
            state(
              "sensor.living_room",
              "21.4",
              name("Living Room"),
              "unit_of_measurement" to JsonPrimitive("°C"),
            ),
          )
          .associateBy { it.entityId }
    )

  private fun card(json: String): CardConfig {
    val raw = Json.parseToJsonElement(json).jsonObject
    return CardConfig((raw["type"] as JsonPrimitive).content, raw)
  }

  /** The tappable nodes of [card] as played back, with their size in dp. */
  private fun tapTargets(card: CardConfig): List<Pair<Float, Float>> {
    val registry = defaultRegistry()
    val theme = haThemeFor(ThemeStyle.TerrazzoHome, false)
    compose.setContent {
      Box(Modifier.background(Color.White).width(381.dp)) {
        CachedCardPreview(
          cacheKey = "touch-target-${card.type}",
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
    val root = compose.onRoot().fetchSemanticsNode()
    val density = root.layoutInfo.density.density
    return generateSequence(listOf(root)) { level ->
        level.flatMap(SemanticsNode::children).ifEmpty { null }
      }
      .flatten()
      .filter { it.config.getOrNull(SemanticsActions.OnClick) != null }
      .map { it.size.width / density to it.size.height / density }
      .toList()
  }

  private fun assertTouchTargets(json: String, atLeast: Int) {
    val targets = tapTargets(card(json))
    assertTrue(targets.size >= atLeast, "at least $atLeast tap targets, found ${targets.size}")
    val small = targets.filter { (w, h) -> w < 47.5f || h < 47.5f }
    assertEquals(emptyList(), small, "tap targets under 48dp (w × h dp) of $targets")
  }

  @Test
  fun mediaControlTransportButtons() =
    assertTouchTargets("""{"type":"media-control","entity":"media_player.office_speaker"}""", 3)

  @Test
  fun pictureGlanceEntityIcons() =
    assertTouchTargets(
      """{"type":"picture-glance","title":"Living room","image":"/local/lr.png",
        "entities":["light.living_room_lamp","light.kitchen_lights",
                    "cover.living_room_blinds","switch.fan"]}""",
      4,
    )

  @Test
  fun todoListItems() =
    assertTouchTargets(
      """{"type":"todo-list","entity":"todo.shopping","title":"Shopping list"}""",
      2,
    )

  @Test
  fun entitiesRows() =
    assertTouchTargets(
      """{"type":"entities","title":"Living Room","entities":[
        "sensor.living_room","light.kitchen_lights","switch.fan"]}""",
      3,
    )
}
