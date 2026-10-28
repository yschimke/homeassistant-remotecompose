package ee.schimke.ha.rc.cards.shutter

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CachedCardPreview
import ee.schimke.ha.rc.InMemoryCardDocumentCache
import ee.schimke.ha.rc.LocalCardDocumentCache
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.androidXExperimentalWrap
import ee.schimke.ha.rc.cardDataSignature
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.haThemeFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
 * The shutter cards are captured once and follow the cover through its bindings alone
 * (live-card-updates.md, path 1): the same document, played under each state, must show what the
 * reference functions ([coverStateLabel], [coverControls]) say for that state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverLiveDocumentTest {

  @get:Rule val compose = createComposeRule()

  private val registry = defaultRegistry().withAllShutters()

  private fun cover(state: String, vararg attributes: Pair<String, Any>) =
    EntityState(
      DOOR,
      state,
      JsonObject(
        mapOf("friendly_name" to JsonPrimitive("Garage Door")) +
          attributes.associate { (k, v) ->
            k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString())
          }
      ),
    )

  /** Every state a door goes through, with and without a position, as the card must draw it. */
  private val states =
    listOf(
      cover("closed", "supported_features" to 15, "current_position" to 0),
      cover("opening", "supported_features" to 15, "current_position" to 40),
      cover("open", "supported_features" to 15, "current_position" to 100),
      cover("open", "supported_features" to 15, "current_position" to 55),
      cover("closing", "supported_features" to 15, "current_position" to 20),
      cover("closed"),
      cover("opening"),
      cover("open", "supported_features" to 3),
      cover("unavailable", "supported_features" to 15),
      cover("unknown", "supported_features" to 11),
    )

  private fun card(type: String) =
    CardConfig(
      type,
      Json.parseToJsonElement("""{"type":"$type","entities":["$DOOR"]}""").jsonObject,
    )

  @Test fun garageCardFollowsTheDoorWithoutReEncoding() = followsTheDoor(card(GARAGE))

  @Test fun shutterCardFollowsTheDoorWithoutReEncoding() = followsTheDoor(card(SHUTTER))

  /**
   * `assumed_state` is the integration's, fixed for a cover: it is part of a card design's shape,
   * not a binding, so a door that reports it is captured as one.
   */
  @Test
  fun assumedStateDoorKeepsItsButtons() =
    followsTheDoor(
      card(GARAGE),
      listOf("closed", "opening", "open", "closing").map { cover(it, "assumed_state" to "true") },
    )

  private fun followsTheDoor(card: CardConfig, states: List<EntityState> = this.states) {
    assertNull(
      registry.cardDataSignature(card, HaSnapshot(states = mapOf(DOOR to states.first()))),
      "a shutter card has no re-encode signature",
    )
    val theme = haThemeFor(ThemeStyle.TerrazzoHome, false)
    val cache = InMemoryCardDocumentCache()
    // Every document the player is handed (once per composition): one capture means one.
    val documents = HashSet<Int>()
    var snapshot by mutableStateOf(HaSnapshot(states = mapOf(DOOR to states.first())))

    compose.setContent {
      CompositionLocalProvider(LocalCardDocumentCache provides cache) {
        Box(Modifier.background(Color.White).width(381.dp)) {
          CachedCardPreview(
            cacheKey = "cover-live",
            profile = androidXExperimentalWrap,
            card = card,
            snapshot = snapshot,
            onDocument = { documents += it.contentHashCode() },
          ) {
            ProvideCardRegistry(registry) {
              ProvideHaTheme(theme) { RenderChild(card, snapshot, RemoteModifier.fillMaxWidth()) }
            }
          }
        }
      }
    }

    for (entity in states) {
      snapshot = HaSnapshot(states = mapOf(DOOR to entity))
      compose.waitForIdle()
      val shown = semantics()
      val where = "${entity.state} ${entity.attributes}: $shown"
      assert(coverStateLabel(entity) in shown) { "state line ${coverStateLabel(entity)} in $where" }
      // The state line can read like a button ("Open"): leave it out of the buttons.
      val buttons = shown.toMutableList().apply { remove(coverStateLabel(entity)) }
      val controls = coverControls(entity)
      for ((name, button) in
        listOf("Open" to controls.open, "Stop" to controls.stop, "Close" to controls.close)) {
        val expected =
          when (button) {
            CoverButtonState.Enabled -> name
            CoverButtonState.Disabled -> "$name (unavailable)"
            CoverButtonState.Hidden -> null
          }
        val drawn = buttons.filter { it == name || it == "$name (unavailable)" }
        assertEquals(listOfNotNull(expected), drawn, "$name button for $where")
      }
    }
    assertEquals(1, documents.size, "one document for every state")
  }

  /** The texts and content descriptions of every node the player shows. */
  private fun semantics(): List<String> {
    val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
    return generateSequence(listOf(root)) { level ->
        level.flatMap(SemanticsNode::children).ifEmpty { null }
      }
      .flatten()
      .flatMap {
        it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } +
          it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
      }
      .toList()
  }

  private companion object {
    const val DOOR = "cover.garage_door"
    const val GARAGE = GarageShutterCardConverter.CARD_TYPE
    const val SHUTTER = EnhancedShutterCardConverter.CARD_TYPE
  }
}
