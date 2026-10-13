package ee.schimke.ha.catalog

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.Color
import ee.schimke.composeai.discovery.*
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.ui.HaGaugeStyle
import ee.schimke.ha.rc.ui.HaModeChipUiData
import ee.schimke.ha.rc.ui.HaTileUiData
import ee.schimke.ha.rc.ui.HaToggleAccentUi
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

/** Every adapter id, one per adapted Tier-2 composable. */
val ADAPTER_IDS =
  listOf(
    "heading",
    "tile",
    "button",
    "toggle-button",
    "entity-row",
    "entities",
    "glance",
    "glance-cell",
    "markdown",
    "unsupported",
    "toggle-switch",
    "metric",
    "gauge",
    "arc-dial",
    "history-graph",
    "statistics-graph",
    "statistic",
    "sensor",
    "logbook",
    "calendar",
    "todo-list",
    "weather-forecast",
    "media-control",
    "alarm-panel",
    "area",
    "clock",
    "picture",
    "picture-glance",
    "picture-elements",
    "picture-entity",
    "bambu-print-status",
    "bambu-print-control",
    "bambu-ams",
    "bambu-spool",
    "vertical-stack",
    "horizontal-stack",
    "grid",
  )

class CatalogContractTest {
  @Test
  fun `fresh app metadata generates a portable explicitly selected catalog`() {
    val records = discoverCatalogComponents()
    val adapters = HomeAssistantCatalog(records)
    adapters.definitions.forEach { definition ->
      assertEquals(
        emptyList(),
        definition.validateAgainst(
          records.components.single { it.canonicalId == definition.component.canonicalId }
        ),
      )
    }
    val pair = adapters.generate(records)
    assertEquals(
      ADAPTER_IDS.map { "homeassistant-remotecompose/$it" }.toSet(),
      pair.catalog.statusSemantics.components.keys,
    )
    val metric =
      pair.catalog.statusSemantics.components.getValue("homeassistant-remotecompose/metric")
    assertEquals("Metric", metric.displayName)
    assertEquals(
      "Card building blocks",
      pair.record.components.single { it.symbol.name == "HaMetric" }.builder?.group,
    )
    assertTrue(pair.catalog.diagnostics.isEmpty())
    val json = Json { encodeDefaults = true }
    val portable =
      json.decodeFromString(
        UiBuilderCatalogFile.serializer(),
        json.encodeToString(UiBuilderCatalogFile.serializer(), pair.catalog),
      )
    assertEquals(pair.catalog, portable)
    val destination = Path.of(requireNotNull(System.getProperty("typedCatalogOutput")))
    destination.toFile().deleteRecursively()
    pair.writeTo(destination)
    assertEquals(
      pair.record,
      json.decodeFromString(
        ComponentRecordFile.serializer(),
        Files.readString(destination.resolve("components.json")),
      ),
    )
    assertEquals(
      pair.catalog,
      json.decodeFromString(
        UiBuilderCatalogFile.serializer(),
        Files.readString(destination.resolve("ui-builder.json")),
      ),
    )
    assertFailsWith<IllegalArgumentException> { pair.writeTo(destination) }
  }

  @Test
  fun `app adapter refuses stale discovery records`() {
    val records = discoverCatalogComponents()
    val catalog = HomeAssistantCatalog(records)
    val stale =
      records
        .newBuilder()
        .also { b ->
          b.components =
            records.components.map { c ->
              c.newBuilder().also { it.signatureKnown = false }.build()
            }
        }
        .build()
    assertFailsWith<IllegalArgumentException> { catalog.generate(stale) }
  }
}

/** Codec decoding: the real models, their unions, icons, colours, enums and refusals. */
class CatalogCodecTest {
  private val catalog = HomeAssistantCatalog(discoverCatalogComponents())

  private fun parse(json: String) = Json.parseToJsonElement(json).jsonObject

  @Test
  fun `every adapted composable is a discovered Tier-2 card and every default round trips`() {
    assertEquals(
      catalogCallables.keys,
      catalog.definitions.map { it.component.symbol.name }.toSet(),
    )
    assertEquals(catalogCallables.size, catalog.definitions.size)
    for (definition in catalog.definitions) for (property in definition.properties) {
      @Suppress("UNCHECKED_CAST") val p = property as AdapterProperty<Any?, Any?>
      assertEquals(p.default, p.unwrap(p.codec.wrap(p.default)), "${definition.id}.${p.name}")
    }
  }

  @Test
  fun `a tile is stored as the real HaTileUiData fields and decodes into the model`() {
    val stored =
      parse(
        """
        {"type":"object","fields":{
          "name":{"type":"string","value":"Porch"},
          "icon":{"type":"string","value":"filled/lockOpen"},
          "accent":{"type":"object","fields":{
            "activeAccent":{"type":"string","value":"#4CAF50"},
            "inactiveAccent":{"type":"string","value":"#FF8F8F8F"},
            "isOn":{"type":"bool","value":false}}},
          "tapAction":{"type":"union","case":"moreInfo","fields":{
            "entityId":{"type":"string","value":"lock.porch"}}}}}
        """
      )
    val tile = catalog.tile.data.unwrap(stored)
    assertEquals(
      HaTileUiData(
        name = "Porch",
        state = "",
        icon = Icons.Filled.LockOpen,
        accent = HaToggleAccentUi(Color(0xFF4CAF50), Color(0xFF8F8F8F), isOn = false),
        tapAction = HaAction.MoreInfo("lock.porch"),
      ),
      tile,
    )
    assertEquals("ee.schimke.ha.rc.ui.HaTileUiData", catalog.tile.data.codec.kotlinType)
  }

  @Test
  fun `icons are a closed Material key set and colours are hex only`() {
    assertEquals("filled/lightbulb", IconCodec.encode(Icons.Filled.Lightbulb).jsonPrimitive.content)
    assertTrue("filled/wbCloudy" in IconCodec.allowedValues.orEmpty())
    assertFailsWith<IllegalArgumentException> { IconCodec.decode(JsonPrimitive("filled/rocket")) }
    assertFailsWith<IllegalArgumentException> { IconCodec.encode(Icons.Filled.Star) }
    assertEquals(Color(0xFFFFC107), ColorCodec.decode(JsonPrimitive("#FFC107")))
    assertEquals(Color.Transparent, ColorCodec.decode(JsonPrimitive("transparent")))
    assertEquals("#FFFFC107", ColorCodec.encode(Color(0xFFFFC107)).jsonPrimitive.content)
    for (refused in listOf("", "primary", "#12")) {
      assertFailsWith<IllegalArgumentException>(refused) {
        ColorCodec.decode(JsonPrimitive(refused))
      }
    }
  }

  @Test
  fun `layout variants are enums of the real members`() {
    assertEquals(setOf("standard", "stacked", "wide"), GaugeStyleCodec.allowedValues)
    assertEquals(
      HaGaugeStyle.Wide,
      catalog.gauge.style.unwrap(parse("""{"type":"string","value":"wide"}""")),
    )
    assertEquals("layout", catalog.weatherForecast.style.name)
    assertFailsWith<IllegalArgumentException> {
      catalog.gauge.style.unwrap(parse("""{"type":"string","value":"Wide"}"""))
    }
  }

  @Test
  fun `every HaAction case round trips and service data is JSON object text`() {
    val actions =
      listOf(
        HaAction.None,
        HaAction.Toggle("light.kitchen"),
        HaAction.MoreInfo("sensor.outside"),
        HaAction.Navigate("/lovelace/1"),
        HaAction.Url("https://www.home-assistant.io"),
        HaAction.CallService(
          "light",
          "turn_on",
          "light.kitchen",
          buildJsonObject { put("brightness_pct", 40) },
        ),
        HaAction.CallService("script", "goodnight"),
        HaAction.AlarmKey("alarm_control_panel.home", "4"),
        HaAction.AlarmPin("alarm_control_panel.home", "1234"),
        HaAction.AlarmIntent("alarm_control_panel.home", "arm_away", 4),
      )
    for (action in actions) assertEquals(action, HaActionCodec.unwrap(HaActionCodec.wrap(action)))
    assertEquals(parse("""{"type":"union","case":"none"}"""), HaActionCodec.wrap(HaAction.None))
    val call =
      parse(
        """
        {"type":"union","case":"callService","fields":{
          "domain":{"type":"string","value":"light"},"service":{"type":"string","value":"turn_on"},
          "serviceData":{"type":"string","value":"{\"brightness_pct\": 40}"}}}
        """
      )
    assertEquals(
      HaAction.CallService(
        "light",
        "turn_on",
        serviceData = buildJsonObject { put("brightness_pct", 40) },
      ),
      HaActionCodec.unwrap(call),
    )
    assertEquals("json", ServiceDataCodec.format)
    for (bad in listOf("[1, 2]", "{not json", "40")) {
      assertFailsWith<IllegalArgumentException>(bad) { ServiceDataCodec.decode(JsonPrimitive(bad)) }
    }
    assertFailsWith<IllegalArgumentException> {
      HaActionCodec.unwrap(parse("""{"type":"union","case":"fireEvent"}"""))
    }
  }

  @Test
  fun `sealed models are unions of their cases`() {
    val chips = listOf(HaModeChipUiData.Static("Heat"), HaModeChipUiData.Toggle(true, "On", "Off"))
    for (chip in chips) assertEquals(chip, ModeChipCodec.unwrap(ModeChipCodec.wrap(chip)))
    for (element in HomeAssistantDefaults.pictureElements.elements) {
      assertEquals(element, PictureElementCodec.unwrap(PictureElementCodec.wrap(element)))
    }
    val dial =
      HomeAssistantDefaults.arcDial.copy(modeChip = null, centerIcon = null, targetFraction = null)
    assertEquals(dial, ArcDialCodec.unwrap(ArcDialCodec.wrap(dial)))
    assertEquals(
      parse("""{"type":"null"}"""),
      ArcDialCodec.wrap(dial).getValue("fields").jsonObject.getValue("modeChip"),
    )
  }

  @Test
  fun `the real model's invariants refuse a malformed value`() {
    val panel = AlarmPanelCodec.wrap(HomeAssistantDefaults.alarmPanel)
    val fields = panel.getValue("fields").jsonObject
    val empty = JsonObject(fields + ("statuses" to parse("""{"type":"list","values":[]}""")))
    assertFailsWith<IllegalArgumentException> {
      AlarmPanelCodec.unwrap(
        buildJsonObject {
          put("type", "object")
          put("fields", empty)
        }
      )
    }
  }

  @Test
  fun `the published catalog offers the icon set and the action cases to an editor`() {
    val json = Json { encodeDefaults = true }
    val published =
      json.encodeToString(
        UiBuilderCatalogFile.serializer(),
        catalog.generate(discoverCatalogComponents()).catalog,
      )
    for (expected in
      listOf(
        "filled/lightbulb",
        "ee.schimke.ha.rc.components.HaAction.CallService",
        "\"union\"",
        "HaPictureElementUiData.StateIcon",
      )) {
      assertTrue(expected in published, "published catalog names $expected")
    }
  }
}
