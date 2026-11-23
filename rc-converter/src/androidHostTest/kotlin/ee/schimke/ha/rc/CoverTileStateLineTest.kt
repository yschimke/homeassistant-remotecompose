package ee.schimke.ha.rc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
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
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxWidth
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.haThemeFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
 * A cover's tile reads HA's default `state` · `current_position` line ("Open · 70 %") from bindings
 * alone (live-card-updates.md, path 1), drawn from its converter or from its card design. The
 * design's template is captured from a placeholder cover in `off`, so a line baked at capture read
 * "Off" for an open garage door whatever its state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverTileStateLineTest {

  @get:Rule val compose = createComposeRule()

  private val registry = defaultRegistry()

  private fun tile(entityId: String) =
    CardConfig(
      "tile",
      buildJsonObject {
        put("type", "tile")
        put("entity", entityId)
      },
    )

  private fun garage(state: String, position: Int? = null) =
    HaSnapshot(
      states =
        mapOf(
          GARAGE to
            EntityState(
              GARAGE,
              state,
              JsonObject(
                buildMap {
                  put("friendly_name", JsonPrimitive("Garage door"))
                  put("device_class", JsonPrimitive("garage"))
                  position?.let { put("current_position", JsonPrimitive(it)) }
                },
              ),
            ),
        ),
    )

  /** Each cover state, with and without a position: HA hides a position of 0 or none. */
  private val states =
    listOf(
      garage("open", 70) to "Open · 70 %",
      garage("open", 100) to "Open · 100 %",
      garage("open") to "Open",
      garage("closed", 0) to "Closed",
      garage("opening", 40) to "Opening · 40 %",
      garage("closing", 15) to "Closing · 15 %",
      garage("stopped", 50) to "Stopped · 50 %",
      garage("unavailable") to "Unavailable",
    )

  private var snapshot by mutableStateOf(garage("open", 70))
  private val documents = HashSet<Int>()

  private fun play(card: CardConfig, template: CardTemplatePlayback?) {
    val theme = haThemeFor(ThemeStyle.TerrazzoHome, false)
    val cache = InMemoryCardDocumentCache()
    compose.setContent {
      CompositionLocalProvider(LocalCardDocumentCache provides cache) {
        Box(Modifier.background(Color.White).width(381.dp)) {
          CachedCardPreview(
            cacheKey = "cover-tile-${template != null}",
            profile = androidXExperimentalWrap,
            card = card,
            snapshot = snapshot,
            template = template,
            onDocument = { documents += it.contentHashCode() },
          ) {
            ProvideCardRegistry(registry) {
              ProvideHaTheme(theme) { RenderChild(card, snapshot, RemoteModifier.fillMaxWidth()) }
            }
          }
        }
      }
    }
    compose.waitForIdle()
  }

  private fun everyStateReads(card: CardConfig, template: CardTemplatePlayback?) {
    play(card, template)
    for ((state, line) in states) {
      snapshot = state
      compose.waitForIdle()
      val shown = texts()
      assertTrue(line in shown, "${state.states.getValue(GARAGE).state} reads $line: $shown")
      assertTrue("Off" !in shown, "never a toggle's Off: $shown")
    }
    assertEquals(1, documents.size, "one document for every state")
  }

  @Test
  fun theConvertersTileFollowsTheCoverWithoutReEncoding() {
    val card = tile(GARAGE)
    assertNull(registry.cardDataSignature(card, snapshot), "a plain cover tile binds its line")
    everyStateReads(card, template = null)
  }

  @Test
  fun theDesignsTileReadsTheCoversStateNotThePlaceholders() {
    val card = tile(GARAGE)
    val key =
      assertNotNull(
        CardDesignTemplates.keyFor(card, snapshot, ThemeStyle.TerrazzoHome, false, registry),
      )
    val context = org.robolectric.RuntimeEnvironment.getApplication()
    val playback =
      CardDesignTemplates.playback(card, CardDesignLibrary.template(context, key, registry))
    everyStateReads(card, playback)
  }

  @Test
  fun theDesignsBindingsCarryTheStateAndThePosition() {
    val card = tile(GARAGE)
    val snapshot = garage("open", 70)
    val key =
      assertNotNull(
        CardDesignTemplates.keyFor(card, snapshot, ThemeStyle.TerrazzoHome, false, registry),
      )
    val context = org.robolectric.RuntimeEnvironment.getApplication()
    val bindings =
      CardDesignTemplates.playback(card, CardDesignLibrary.template(context, key, registry))
        .bindings(snapshot, SystemHaClock)
    val slot = CardDesignSlots.slotId("cover", 0)
    assertEquals("Open", bindings.strings["$slot.state"])
    assertEquals(70f, bindings.floats["$slot.current_position"])
    assertEquals(true, bindings.booleans["$slot.is_on"])
  }

  @Test
  fun aLightAndASwitchStillReadOnAndOff() {
    for (id in listOf("light.kitchen", "switch.fan")) {
      for (state in listOf("on", "off")) {
        val card = tile(id)
        val snapshot = HaSnapshot(states = mapOf(id to EntityState(id, state)))
        val key =
          assertNotNull(
            CardDesignTemplates.keyFor(card, snapshot, ThemeStyle.TerrazzoHome, false, registry),
          )
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val bindings =
          CardDesignTemplates.playback(card, CardDesignLibrary.template(context, key, registry))
            .bindings(snapshot, SystemHaClock)
        val slot = CardDesignSlots.slotId(id.substringBefore('.'), 0)
        assertEquals(if (state == "on") "On" else "Off", bindings.strings["$slot.state"])
        assertNull(bindings.floats["$slot.current_position"], "$id has no position")
      }
    }
  }

  /** The texts and content descriptions of every node the player shows. */
  private fun texts(): List<String> {
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
    const val GARAGE = "cover.garage_door"
  }
}
