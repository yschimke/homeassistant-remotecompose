package ee.schimke.ha.catalog

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.discovery.AdapterValueCodec
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.renderer.sdk.*
import ee.schimke.ha.catalog.HomeAssistantDefaults as D
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.ui.*
import java.io.File
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A literal wrapper for one property, as a design stores it. */
private fun <T> wrapped(codec: AdapterValueCodec<T>, value: T): JsonObject = codec.wrap(value)

private fun dataOf(wrapper: JsonObject) = buildJsonObject { put("data", wrapper) }

private fun styledOf(data: JsonObject, name: String, style: JsonObject) = buildJsonObject {
  put("data", data)
  put(name, style)
}

private fun string(value: String) = buildJsonObject {
  put("type", "string")
  put("value", value)
}

/**
 * One parity case: the adapter [id] fed [properties] must draw exactly the pixels of the direct
 * [native] call of the real Tier-2 card, in a [widthDp] x [heightDp] slot.
 */
private class ParityCase(
  val id: String,
  val widthDp: Int,
  val heightDp: Int,
  val properties: JsonObject,
  val native: @Composable () -> Unit,
)

private fun parityCases(): Map<String, ParityCase> {
  fun case(
    id: String,
    width: Int,
    height: Int,
    properties: JsonObject,
    native: @Composable () -> Unit,
  ) = ParityCase("homeassistant-remotecompose/$id", width, height, properties, native)
  return linkedMapOf(
    "heading" to
      case("heading", 400, 64, dataOf(wrapped(HeadingCodec, D.heading))) { HaHeading(D.heading) },
    "tile" to case("tile", 400, 80, dataOf(wrapped(TileCodec, D.tile))) { HaTile(D.tile) },
    "button" to
      case("button", 160, 160, dataOf(wrapped(ButtonCodec, D.button))) { HaButton(D.button) },
    "toggle-button" to
      case("toggle-button", 160, 160, dataOf(wrapped(ButtonCodec, D.button))) {
        HaToggleButton(D.button)
      },
    "entity-row" to
      case("entity-row", 400, 56, dataOf(wrapped(EntityRowCodec, D.entityRow))) {
        HaEntityRow(D.entityRow)
      },
    "entities" to
      case("entities", 400, 200, dataOf(wrapped(EntitiesCodec, D.entities))) {
        HaEntities(D.entities)
      },
    "glance" to
      case("glance", 400, 140, dataOf(wrapped(GlanceCodec, D.glance))) { HaGlance(D.glance) },
    "glance-cell" to
      case("glance-cell", 120, 120, dataOf(wrapped(GlanceCellCodec, D.glance.cells.first()))) {
        HaGlanceCell(D.glance.cells.first())
      },
    "markdown" to
      case("markdown", 400, 160, dataOf(wrapped(MarkdownCodec, D.markdown))) {
        HaMarkdown(D.markdown)
      },
    "unsupported" to
      case("unsupported", 400, 100, dataOf(wrapped(UnsupportedCodec, D.unsupported))) {
        HaUnsupported(D.unsupported)
      },
    "toggle-switch" to
      case(
        "toggle-switch",
        120,
        64,
        buildJsonObject {
          put(
            "initiallyOn",
            buildJsonObject {
              put("type", "bool")
              put("value", false)
            },
          )
          put("activeAccent", string("#FF4CAF50"))
          put("inactiveAccent", string("#8F8F8F"))
          put("tapAction", wrapped(HaActionCodec, HaAction.Toggle("switch.porch")))
        },
      ) {
        HaToggleSwitch(
          initiallyOn = false,
          activeAccent = D.Green,
          inactiveAccent = D.Grey,
          tapAction = HaAction.Toggle("switch.porch"),
        )
      },
    "gauge" to
      case(
        "gauge",
        187,
        150,
        styledOf(
          wrapped(GaugeCodec, D.gauge),
          "style",
          wrapped(GaugeStyleCodec, HaGaugeStyle.Stacked),
        ),
      ) {
        HaGauge(D.gauge, style = HaGaugeStyle.Stacked)
      },
    "arc-dial" to
      case(
        "arc-dial",
        240,
        290,
        styledOf(
          wrapped(ArcDialCodec, D.arcDial),
          "style",
          wrapped(ArcDialStyleCodec, HaArcDialStyle.Standard),
        ),
      ) {
        HaArcDial(D.arcDial)
      },
    "history-graph" to
      case("history-graph", 381, 180, dataOf(wrapped(HistoryGraphCodec, D.historyGraph))) {
        HaHistoryGraph(D.historyGraph)
      },
    "statistics-graph" to
      case(
        "statistics-graph",
        381,
        180,
        dataOf(wrapped(StatisticsGraphCodec, D.statisticsGraph)),
      ) {
        HaStatisticsGraph(D.statisticsGraph)
      },
    "statistic" to
      case("statistic", 240, 110, dataOf(wrapped(StatisticCardCodec, D.statisticCard))) {
        HaStatisticCard(D.statisticCard)
      },
    "sensor" to
      case("sensor", 320, 130, dataOf(wrapped(SensorCardCodec, D.sensorCard))) {
        HaSensorCard(D.sensorCard)
      },
    "logbook" to
      case("logbook", 381, 165, dataOf(wrapped(LogbookCodec, D.logbook))) { HaLogbook(D.logbook) },
    "calendar" to
      case("calendar", 381, 220, dataOf(wrapped(CalendarCodec, D.calendar))) {
        HaCalendar(D.calendar)
      },
    "todo-list" to
      case("todo-list", 320, 220, dataOf(wrapped(TodoListCodec, D.todoList))) {
        HaTodoList(D.todoList)
      },
    "weather-forecast" to
      case(
        "weather-forecast",
        381,
        100,
        styledOf(
          wrapped(WeatherForecastCodec, D.weather),
          "layout",
          wrapped(CardLayoutCodec, HaCardLayout.Wide),
        ),
      ) {
        HaWeatherForecast(D.weather, layout = HaCardLayout.Wide)
      },
    "media-control" to
      case(
        "media-control",
        381,
        168,
        styledOf(
          wrapped(MediaControlCodec, D.mediaControl),
          "layout",
          wrapped(CardLayoutCodec, HaCardLayout.Standard),
        ),
      ) {
        HaMediaControl(D.mediaControl)
      },
    "alarm-panel" to
      case(
        "alarm-panel",
        381,
        200,
        styledOf(
          wrapped(AlarmPanelCodec, D.alarmPanel),
          "layout",
          wrapped(CardLayoutCodec, HaCardLayout.Standard),
        ),
      ) {
        HaAlarmPanel(D.alarmPanel)
      },
    "area" to case("area", 280, 140, dataOf(wrapped(AreaCodec, D.area))) { HaArea(D.area) },
    "clock" to case("clock", 200, 100, dataOf(wrapped(ClockCodec, D.clock))) { HaClock(D.clock) },
    "picture" to
      case("picture", 320, 140, dataOf(wrapped(PictureCodec, D.picture))) { HaPicture(D.picture) },
    "picture-glance" to
      case("picture-glance", 381, 168, dataOf(wrapped(PictureGlanceCodec, D.pictureGlance))) {
        HaPictureGlance(D.pictureGlance)
      },
    "picture-elements" to
      case(
        "picture-elements",
        381,
        180,
        dataOf(wrapped(PictureElementsCodec, D.pictureElements)),
      ) {
        HaPictureElements(D.pictureElements)
      },
    "picture-entity" to
      case("picture-entity", 312, 160, dataOf(wrapped(PictureEntityCodec, D.pictureEntity))) {
        HaPictureEntity(D.pictureEntity)
      },
    "bambu-print-status" to
      case(
        "bambu-print-status",
        381,
        186,
        dataOf(wrapped(BambuPrintStatusCodec, D.bambuPrintStatus)),
      ) {
        HaBambuPrintStatus(D.bambuPrintStatus)
      },
    "bambu-print-control" to
      case(
        "bambu-print-control",
        381,
        124,
        dataOf(wrapped(BambuPrintControlCodec, D.bambuPrintControl)),
      ) {
        HaBambuPrintControl(D.bambuPrintControl)
      },
    "bambu-ams" to
      case("bambu-ams", 381, 130, dataOf(wrapped(BambuAmsCodec, D.bambuAms))) {
        HaBambuAms(D.bambuAms)
      },
    "bambu-spool" to
      case("bambu-spool", 381, 96, dataOf(wrapped(BambuSpoolDetailCodec, D.bambuSpool))) {
        HaBambuSpool(D.bambuSpool)
      },
    // An omitted property is the adapter's insertion default.
    "tile-default" to case("tile", 400, 80, JsonObject(emptyMap())) { HaTile(D.tile) },
    // A design saved against the first, stand-in catalog keeps its flat literals.
    "legacy-tile" to
      case(
        "tile",
        400,
        80,
        buildJsonObject {
          put("name", string("Kitchen light"))
          put("state", string("Off"))
        },
      ) {
        HaTile(D.tile.copy(name = "Kitchen light", state = "Off"))
      },
    "legacy-entities" to
      case(
        "entities",
        400,
        160,
        Json.parseToJsonElement(
            """
            {"title":{"type":"string","value":""},
             "rows":{"type":"list","values":[
               {"type":"object","fields":{"name":{"type":"string","value":"Porch"},
                 "state":{"type":"string","value":"Off"}}}]}}
            """
          )
          .jsonObject,
      ) {
        HaEntities(
          HaEntitiesUiData(
            title = null,
            rows = listOf(D.entities.rows.first().copy(name = "Porch", state = "Off")),
          )
        )
      },
  )
}

/** Each real Tier-2 card, drawn directly and through its typed adapter, pixel for pixel. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CatalogRenderTest(private val name: String) {
  companion object {
    @JvmStatic
    @Parameters(name = "{0}")
    fun cases(): List<Array<Any>> = parityCases().keys.map { arrayOf<Any>(it) }
  }

  @get:Rule val compose = createComposeRule()

  @Test
  fun `native Remote Compose card and typed adapter render the same pixels`() {
    val case = parityCases().getValue(name)
    val catalog = HomeAssistantCatalog(discoverCatalogComponents())
    val adapter = requireNotNull(catalog.registry()[case.id])
    var adapted by mutableStateOf(false)
    compose.setContent {
      MaterialTheme {
        Box(Modifier.size(case.widthDp.dp, case.heightDp.dp).testTag("catalog")) {
          if (!adapted) case.native() else adapter(canvasScope(case.id, case.properties))
        }
      }
    }
    compose.waitForIdle()
    val directImage = compose.onNodeWithTag("catalog").captureToImage()
    val directory =
      File(requireNotNull(System.getProperty("typedCatalogRenders"))).also { it.mkdirs() }
    directory.resolve("native-$name.png").outputStream().use {
      directImage.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
    val direct = directImage.toPixelMap()
    compose.runOnIdle { adapted = true }
    compose.waitForIdle()
    // An invalid property draws the bridge's diagnostic text instead of the card.
    compose.onAllNodesWithText("invalid properties", substring = true).assertCountEquals(0)
    val typedImage = compose.onNodeWithTag("catalog").captureToImage()
    directory.resolve("typed-$name.png").outputStream().use {
      typedImage.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
    val typed = typedImage.toPixelMap()
    assertEquals(direct.width, typed.width)
    assertEquals(direct.height, typed.height)
    val colors = mutableSetOf<androidx.compose.ui.graphics.Color>()
    for (y in 0 until direct.height) for (x in 0 until direct.width) {
      colors += direct[x, y]
      assertEquals(direct[x, y], typed[x, y], "$name pixel $x,$y")
    }
    assertTrue(colors.size > 2, "the native player must draw real $name content")
  }
}

/** The SDK's node scope for one adapter, with [content] standing in for every slot. */
internal fun canvasScope(
  id: String,
  properties: JsonObject = JsonObject(emptyMap()),
  content: @Composable () -> Unit = {},
) =
  CanvasNodeScope(
    UiBuilderNode(id = "node", componentId = id, properties = properties),
    Modifier,
    CanvasMode.Device,
    { _, _ -> content() },
    { _, _ -> },
    { 0 },
    { _, _, _ -> },
    {},
    { _, _ -> },
    {},
  )

/** The real layout composables receive the canvas's slot children. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CatalogSlotTest {
  @get:Rule val compose = createComposeRule()

  private fun assertSlot(id: (HomeAssistantCatalog) -> String, text: String) {
    val catalog = HomeAssistantCatalog(discoverCatalogComponents())
    val adapter = requireNotNull(catalog.registry()[id(catalog)])
    compose.setContent { adapter(canvasScope(id(catalog)) { Text(text) }) }
    compose.onNodeWithText(text).assertExists()
  }

  @Test
  fun `actual app stack receives catalog slot children`() =
    assertSlot({ it.stack.id }, "Column child")

  @Test
  fun `actual app horizontal stack receives catalog slot children`() =
    assertSlot({ it.horizontalStack.id }, "Row child")

  @Test
  fun `actual app grid receives catalog slot children`() = assertSlot({ it.grid.id }, "Grid child")
}
