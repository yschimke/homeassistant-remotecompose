package ee.schimke.ha.rc

import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.defaultRegistry
import kotlin.test.Test
import kotlin.test.assertFalse
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
 * Which cards show the history graph's single "No history in …" empty state: the `history-graph`
 * card does; the `sensor` card, which reuses the same layout for one row, keeps its current value.
 * Checked on the encoded document's text.
 */
@Suppress("RestrictedApi")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HistoryEmptyStateCaptureTest {

  private val registry = defaultRegistry()

  private val snapshot =
    HaSnapshot(
      states =
        mapOf(
          "sensor.temp" to
            EntityState(
              "sensor.temp",
              "21.5",
              JsonObject(
                mapOf(
                  "friendly_name" to JsonPrimitive("Living room"),
                  "unit_of_measurement" to JsonPrimitive("°C"),
                  "device_class" to JsonPrimitive("temperature"),
                )
              ),
            )
        )
    )

  private fun documentText(card: CardConfig): String = runBlocking {
    val doc =
      captureCardDocument(
        context = org.robolectric.RuntimeEnvironment.getApplication(),
        widthPx = 720,
        heightPx = 400,
        registry = registry,
        card = card,
        snapshot = snapshot,
        forceRefresh = true,
      )
    String(doc.bytes, Charsets.UTF_8)
  }

  /** The text of [card] captured in a fixed launcher / Wear cell [widthDp] × [heightDp]. */
  private fun fixedCellText(
    card: CardConfig,
    widthDp: Int,
    heightDp: Int,
    axis: BreakpointAxis = BreakpointAxis.Width,
  ): String = runBlocking {
    val context = org.robolectric.RuntimeEnvironment.getApplication()
    val density = context.resources.displayMetrics.density
    val converter = registry.get(card.type)!!
    val captured =
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
        ProvideCardSizeMode(CardSizeMode.Fixed) {
          ProvideFixedCanvasAxis(axis) { converter.Render(card, snapshot) }
        }
      }
    String(captured.bytes, Charsets.UTF_8)
  }

  private fun historyGraph(title: String?) =
    CardConfig(
      type = "history-graph",
      raw =
        buildJsonObject {
          put("type", "history-graph")
          title?.let { put("title", it) }
          put("hours_to_show", 24)
          putJsonArray("entities") { add("sensor.temp") }
        },
    )

  /**
   * A Wear small slot (184 dp wide after padding, over the identity threshold, too short for a
   * title over a sentence) uses the short "No history", never the full sentence.
   */
  @Test
  fun aWearSlotUsesTheShortEmptyState() {
    val text = fixedCellText(historyGraph("Temperature"), 184, 44, BreakpointAxis.Height)
    assertTrue("No history" in text, "the Wear slot shows the short empty state")
    assertFalse("No history in the" in text, "the Wear slot never shows the full sentence")
  }

  /**
   * A launcher widget gates on width at runtime, so its document carries both tiers: the narrow
   * identity tier's short "No history", and the wide tier's full empty state, which has room in a
   * launcher cell (the one-line Wear form would leave most of a large widget blank).
   */
  @Test
  fun aLauncherWidgetKeepsTheFullEmptyState() {
    val text = fixedCellText(historyGraph("Temperature"), 288, 252)
    assertTrue("No history in the last 24 hours" in text, "the wide tier keeps the full sentence")
    assertTrue("No history" in text.replace("No history in the last 24 hours", ""))
  }

  @Test
  fun aSensorWithNoHistoryKeepsItsValue() {
    val text =
      documentText(
        CardConfig(
          type = "sensor",
          raw =
            buildJsonObject {
              put("type", "sensor")
              put("entity", "sensor.temp")
            },
        )
      )
    assertTrue("21.5" in text, "the sensor's current value is in the document")
    assertFalse("No history" in text, "a sensor card never shows the history empty state")
  }

  @Test
  fun aHistoryGraphWithNoHistoryShowsOneEmptyState() {
    val text =
      documentText(
        CardConfig(
          type = "history-graph",
          raw =
            buildJsonObject {
              put("type", "history-graph")
              put("hours_to_show", 48)
              putJsonArray("entities") { add("sensor.temp") }
            },
        )
      )
    assertTrue("No history in the last 2 days" in text)
  }
}
