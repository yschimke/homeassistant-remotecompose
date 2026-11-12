package ee.schimke.ha.rc

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxWidth
import ee.schimke.composeai.rcplayer.protocol.RcAccessibilitySemantics
import ee.schimke.composeai.rcplayer.protocol.RcClickModifier
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcOperation
import ee.schimke.composeai.rcplayer.protocol.RcPaddingModifier
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A card's tap targets say what they are in the document itself: each click is written with the
 * button role, and a target with nothing inside to name it (an icon-only stepper, a picture, a
 * picture element) carries its own description. The creation API's `clickable` writes neither
 * unless asked, so an unnamed target reaches a screen reader as an unlabelled element.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardTapTargetSemanticsTest {

  @get:Rule val compose = createComposeRule()

  private val registry = defaultRegistry()

  private fun state(id: String, state: String, vararg attrs: Pair<String, JsonElement>) =
    EntityState(id, state, JsonObject(attrs.toMap()))

  private fun name(name: String) = "friendly_name" to JsonPrimitive(name)

  private val snapshot =
    HaSnapshot(
      states =
        listOf(
            state(
              "light.kitchen",
              "on",
              name("Kitchen"),
              "brightness" to JsonPrimitive(153),
              "supported_color_modes" to JsonArray(listOf(JsonPrimitive("brightness"))),
            ),
            state("light.living_room_lamp", "on", name("Lamp")),
            state("cover.living_room_blinds", "open", name("Blinds")),
            state("camera.printer", "idle", name("P2S camera")),
            state(
              "sensor.living_room",
              "21.4",
              name("Living Room"),
              "unit_of_measurement" to JsonPrimitive("°C"),
            ),
          )
          .associateBy { it.entityId },
    )

  private fun card(json: String): CardConfig {
    val raw = Json.parseToJsonElement(json).jsonObject
    return CardConfig((raw["type"] as JsonPrimitive).content, raw)
  }

  /** One tap target as the document writes it. */
  private class Target(val semantics: List<RcAccessibilitySemantics>, val label: String?) {
    val isButton
      get() = semantics.any { it.role == RcAccessibilitySemantics.ROLE_BUTTON }
  }

  /** A component's modifiers: what follows [from] up to where its content starts. */
  private fun List<RcOperation>.modifiersFrom(from: Int): List<RcOperation> =
    drop(from).takeWhile { it !is RcLayoutContent }

  /** The components of [card]'s captured document that carry a click, in document order. */
  private fun targets(card: CardConfig): List<Pair<Target, List<RcOperation>>> {
    val bytes = runBlocking {
      captureCardDocument(
          context = RuntimeEnvironment.getApplication(),
          widthPx = 381,
          heightPx = 320,
          registry = registry,
          card = card,
          snapshot = snapshot,
          forceRefresh = true,
        )
        .bytes
    }
    val operations = RcDocumentCodec.decode(bytes, RcWireLimits()).operations
    val text = operations.filterIsInstance<RcTextData>().associate { it.id to it.text }
    return operations.indices
      .filter { operations[it] is RcClickModifier }
      .map { click ->
        // Back to the layout op that opens the component the click belongs to.
        val start =
          (click downTo 0).first { operations[it]::class.simpleName.orEmpty().endsWith("Layout") }
        val modifiers = operations.modifiersFrom(start)
        val semantics = modifiers.filterIsInstance<RcAccessibilitySemantics>()
        val label = semantics.firstNotNullOfOrNull { s ->
          s.contentDescriptionId.takeUnless { it == 0 }?.let(text::get)
        }
        Target(semantics, label) to modifiers
      }
  }

  private fun labels(card: CardConfig): List<String?> = targets(card).map { it.first.label }

  private val lightCard = """{"type":"light","entity":"light.kitchen"}"""

  private val pictureElements =
    """{"type":"picture-elements","image":"/local/floorplan.png",
      "elements":[
        {"type":"state-icon","entity":"light.living_room_lamp","style":{"left":"22%","top":"30%"}},
        {"type":"state-icon","entity":"cover.living_room_blinds","style":{"left":"68%","top":"22%"}},
        {"type":"service-button","title":"All off",
         "tap_action":{"action":"call-service","service":"light.turn_off",
                       "target":{"entity_id":"light.living_room_lamp"}}}
      ]}"""

  @Test
  fun everyTapTargetIsWrittenAsAButton() {
    val cards =
      listOf(
        lightCard,
        pictureElements,
        """{"type":"tile","entity":"sensor.living_room"}""",
        """{"type":"entities","title":"Living Room",
            "entities":["light.living_room_lamp","sensor.living_room"]}""",
        """{"type":"button","entity":"light.living_room_lamp"}""",
        """{"type":"picture-entity","entity":"camera.printer"}""",
      )
    for (json in cards) {
      val targets = targets(card(json)).map { it.first }
      assertTrue(targets.isNotEmpty(), "a tap target in $json")
      assertTrue(targets.all { it.isButton }, "every click has the button role in $json")
    }
  }

  @Test
  fun lightCardSteppersAreNamed() {
    val labels = labels(card(lightCard))
    assertTrue("Decrease" in labels && "Increase" in labels, "stepper targets, of $labels")
  }

  @Test
  fun aPictureWithItsNameHiddenIsNamedOnItsTarget() {
    val hidden =
      labels(
        card(
          """{"type":"picture-entity","entity":"camera.printer",
            "show_name":false,"show_state":false}""",
        ),
      )
    assertEquals(listOf<String?>("P2S camera"), hidden)
    // Its name strip says it otherwise, and the target doesn't say it again.
    val shown = labels(card("""{"type":"picture-entity","entity":"camera.printer"}"""))
    assertEquals(listOf<String?>(null), shown)
  }

  @Test
  fun pictureElementTargetsAreNamedAndDoNotIncludeTheirPosition() {
    val targets = targets(card(pictureElements))
    assertEquals(listOf("element", "element", null), targets.map { it.first.label })
    // An icon's position is the padding of a box around it. On the target itself, the tap reached
    // from the card's corner to the icon.
    val icons = targets.take(2)
    assertTrue(
      icons.all { (_, modifiers) -> modifiers.none { it is RcPaddingModifier } },
      "a state icon's tap target has no padding of its own",
    )
  }

  @Test
  fun pictureElementIconsAreFullTouchTargets() {
    val card = card(pictureElements)
    val theme = haThemeFor(ThemeStyle.TerrazzoHome, false)
    compose.setContent {
      Box(Modifier.width(381.dp)) {
        CachedCardPreview(
          cacheKey = "tap-target-semantics",
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
    val icons =
      generateSequence(listOf(root)) { level ->
          level.flatMap(SemanticsNode::children).ifEmpty { null }
        }
        .flatten()
        .filter { it.config.getOrNull(SemanticsActions.OnClick) != null }
        .filter { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("element") }
        .map { it.size.width / density to it.size.height / density }
        .toList()
    assertEquals(2, icons.size, "named state-icon targets")
    assertTrue(icons.all { (w, h) -> w >= 47.5f && h >= 47.5f }, "48dp targets, of $icons")
  }
}
