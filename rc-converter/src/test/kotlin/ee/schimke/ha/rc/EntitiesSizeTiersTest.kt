package ee.schimke.ha.rc

import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.compose.runtime.CompositionLocalProvider
import ee.schimke.composeai.rcplayer.edit.namedVariables
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.defaultRegistry
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The entities card's Fixed-mode size tiers: every tier is a list of rows, and the rows register
 * the live `<entity>.is_on` booleans a design-template bake fills in. Pixel parity between baked
 * and direct widgets is in `LauncherDesignsTest` and the Wear module's `SlotWidgetDesignTest`.
 */
@Suppress("RestrictedApi")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EntitiesSizeTiersTest {

  private val registry = defaultRegistry()

  private fun entity(id: String, state: String, name: String) =
    EntityState(id, state, JsonObject(mapOf("friendly_name" to JsonPrimitive(name))))

  private val snapshot =
    HaSnapshot(
      states =
        listOf(
            entity("sensor.temp", "21.5", "Living Room"),
            entity("light.lamp", "on", "Office lamp"),
            entity("switch.coffee", "off", "Coffee maker"),
            entity("lock.door", "locked", "Front door"),
          )
          .associateBy { it.entityId }
    )

  private val card =
    CardConfig(
      type = "entities",
      raw =
        buildJsonObject {
          put("type", "entities")
          put("title", "Downstairs")
          putJsonArray("entities") {
            add("sensor.temp")
            add("light.lamp")
            add("switch.coffee")
            add("lock.door")
          }
        },
    )

  /**
   * [card] captured in a fixed [widthDp] × [heightDp] cell. With [captureSize] the gate selects its
   * tier at capture time, as a launcher capture does; without it every tier is recorded.
   */
  private fun capture(
    widthDp: Int,
    heightDp: Int,
    axis: BreakpointAxis,
    captureSize: Boolean = false,
  ): ByteArray = runBlocking {
    val context = org.robolectric.RuntimeEnvironment.getApplication()
    val density = context.resources.displayMetrics.density
    val converter = registry.get(card.type)!!
    captureSingleRemoteDocument(
        context = context,
        creationDisplayInfo =
          RemoteCreationDisplayInfo(
            (widthDp * density).toInt(),
            (heightDp * density).toInt(),
            context.resources.configuration.densityDpi,
          ),
        profile = RcPlatformProfiles.ANDROIDX,
      ) {
        CompositionLocalProvider(
          LocalWidgetCaptureSizeDp provides
            (if (captureSize) WidgetCaptureSizeDp(widthDp, heightDp) else null)
        ) {
          ProvideCardSizeMode(CardSizeMode.Fixed) {
            ProvideFixedCanvasAxis(axis) { converter.Render(card, snapshot) }
          }
        }
      }
      .bytes
  }

  private fun names(bytes: ByteArray): List<String> =
    RcDocumentCodec.decode(bytes, RcWireLimits()).namedVariables.map { it.name }

  private fun assertRows(bytes: ByteArray, rows: List<String>, where: String) {
    val text = String(bytes, Charsets.UTF_8)
    for (row in rows) assertTrue(row in text, "$where keeps the $row row")
  }

  private val allRows = listOf("Living Room", "Office lamp", "Coffee maker", "Front door")

  /**
   * A launcher capture records only the tier its size selects, so both the dense list and the
   * expanded rows register the live `<entity>.is_on` booleans a design-template bake fills in.
   */
  @Test
  fun eachLauncherTierRegistersTheOnOffBindingsForTheBake() {
    val dense = capture(216, 168, BreakpointAxis.Width, captureSize = true)
    assertTrue(names(dense).any { it.endsWith("light.lamp.is_on") }, "dense: ${names(dense)}")
    assertRows(dense, allRows, "the 3x2 widget")

    val expanded = capture(288, 252, BreakpointAxis.Width, captureSize = true)
    assertTrue(
      names(expanded).any { it.endsWith("light.lamp.is_on") },
      "expanded: ${names(expanded)}",
    )
    assertRows(expanded, allRows, "the 4x3 widget")
  }

  /** Recording both launcher tiers (no capture size), every row is still in the document. */
  @Test
  fun anUnsizedLauncherCaptureKeepsEveryRow() {
    assertRows(capture(288, 252, BreakpointAxis.Width), allRows, "the unsized launcher widget")
  }

  /**
   * A launcher widget too short for every row expanded keeps the dense list, whatever its width.
   */
  @Test
  fun aShortWideLauncherKeepsTheDenseList() {
    val bytes = capture(360, 168, BreakpointAxis.Width, captureSize = true)
    assertRows(bytes, allRows, "the 5x2 widget")
    assertTrue(names(bytes).any { it.endsWith("light.lamp.is_on") }, "dense rows: ${names(bytes)}")
  }

  /**
   * Both Wear tiers are lists of rows sharing one set of live `<entity>.is_on` looks, so a baked
   * Wear template shows each entity's real state.
   */
  @Test
  fun wearTiersAreRowListsWithSharedOnOffBindings() {
    val bytes = capture(184, 96, BreakpointAxis.Height)
    assertTrue(names(bytes).any { it.endsWith("light.lamp.is_on") }, "wear: ${names(bytes)}")
    assertRows(bytes, listOf("Living Room", "Office lamp", "Coffee maker"), "the Wear slot")
    assertTrue("Downstairs" in String(bytes, Charsets.UTF_8), "the large Wear slot keeps the title")
  }
}
