package ee.schimke.ha.rc

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
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.haThemeFor
import kotlin.test.Test
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
 * A card that scrolls back into view replays its cached document, whose bytes carry the values of
 * their first capture. Its first frame must already show the live snapshot, not the stale bake —
 * otherwise every gauge, toggle and label tweens from the old value to the new one on each load
 * (docs/architecture/live-card-updates.md § First frame).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FirstFrameLiveValuesTest {

  @get:Rule val compose = createComposeRule()

  private fun snapshot(value: String) =
    HaSnapshot(
      states =
        mapOf(
          SENSOR to
            EntityState(
              SENSOR,
              value,
              JsonObject(
                mapOf(
                  "friendly_name" to JsonPrimitive("Living Room"),
                  "unit_of_measurement" to JsonPrimitive("°C"),
                )
              ),
            )
        )
    )

  @Test
  fun cachedDocumentShowsLiveValueOnFirstFrame() {
    val raw = Json.parseToJsonElement("""{"type":"entity","entity":"$SENSOR"}""").jsonObject
    val card = CardConfig("entity", raw)
    val registry = defaultRegistry()
    val theme = haThemeFor(ThemeStyle.TerrazzoHome, false)
    val cache = InMemoryCardDocumentCache()
    var shown by mutableStateOf(true)
    var snapshot by mutableStateOf(snapshot("21.4"))

    compose.setContent {
      CompositionLocalProvider(LocalCardDocumentCache provides cache) {
        if (shown) {
          Box(Modifier.background(Color.White).width(381.dp)) {
            CachedCardPreview(
              cacheKey = "first-frame",
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
    }
    compose.waitForIdle()
    assertTrue("21.4" in texts().joinToString(), "captured value shows: ${texts()}")

    // Scrolled away; the entity changes while the card is off screen.
    shown = false
    snapshot = snapshot("23.9")
    compose.waitForIdle()

    // Scrolled back: step frame by frame. The first frame with any text must already read the
    // live value, and no frame may show the stale bake on the way.
    compose.mainClock.autoAdvance = false
    shown = true
    val frames =
      List(20) {
        compose.mainClock.advanceTimeByFrame()
        texts().joinToString()
      }
    val drawn = frames.filter { it.isNotEmpty() }
    assertTrue(drawn.isNotEmpty(), "the card drew text within 20 frames")
    assertTrue(drawn.none { "21.4" in it }, "no frame shows the stale bake: $drawn")
    assertTrue("23.9" in drawn.first(), "first drawn frame shows the live value: $drawn")
  }

  private fun texts(): List<String> {
    val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
    return generateSequence(listOf(root)) { level ->
        level.flatMap(SemanticsNode::children).ifEmpty { null }
      }
      .flatten()
      .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }
      .toList()
  }

  private companion object {
    const val SENSOR = "sensor.living_room"
  }
}
