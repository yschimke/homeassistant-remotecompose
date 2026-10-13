package ee.schimke.ha.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import ee.schimke.composeai.discovery.*
import ee.schimke.composeai.uibuilder.renderer.sdk.*
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.ui.HaAlarmPanel
import ee.schimke.ha.rc.ui.HaArcDial
import ee.schimke.ha.rc.ui.HaArcDialStyle
import ee.schimke.ha.rc.ui.HaArea
import ee.schimke.ha.rc.ui.HaBambuAms
import ee.schimke.ha.rc.ui.HaBambuPrintControl
import ee.schimke.ha.rc.ui.HaBambuPrintStatus
import ee.schimke.ha.rc.ui.HaBambuSpool
import ee.schimke.ha.rc.ui.HaButton
import ee.schimke.ha.rc.ui.HaButtonUiData
import ee.schimke.ha.rc.ui.HaCalendar
import ee.schimke.ha.rc.ui.HaCardLayout
import ee.schimke.ha.rc.ui.HaClock
import ee.schimke.ha.rc.ui.HaEntities
import ee.schimke.ha.rc.ui.HaEntitiesUiData
import ee.schimke.ha.rc.ui.HaEntityRow
import ee.schimke.ha.rc.ui.HaEntityRowUiData
import ee.schimke.ha.rc.ui.HaGauge
import ee.schimke.ha.rc.ui.HaGaugeStyle
import ee.schimke.ha.rc.ui.HaGlance
import ee.schimke.ha.rc.ui.HaGlanceCell
import ee.schimke.ha.rc.ui.HaGlanceUiData
import ee.schimke.ha.rc.ui.HaGrid
import ee.schimke.ha.rc.ui.HaHeading
import ee.schimke.ha.rc.ui.HaHeadingUiData
import ee.schimke.ha.rc.ui.HaHistoryGraph
import ee.schimke.ha.rc.ui.HaHorizontalStack
import ee.schimke.ha.rc.ui.HaLogbook
import ee.schimke.ha.rc.ui.HaMarkdown
import ee.schimke.ha.rc.ui.HaMediaControl
import ee.schimke.ha.rc.ui.HaMetric
import ee.schimke.ha.rc.ui.HaPicture
import ee.schimke.ha.rc.ui.HaPictureElements
import ee.schimke.ha.rc.ui.HaPictureEntity
import ee.schimke.ha.rc.ui.HaPictureGlance
import ee.schimke.ha.rc.ui.HaSensorCard
import ee.schimke.ha.rc.ui.HaStatisticCard
import ee.schimke.ha.rc.ui.HaStatisticsGraph
import ee.schimke.ha.rc.ui.HaTile
import ee.schimke.ha.rc.ui.HaTileUiData
import ee.schimke.ha.rc.ui.HaTodoList
import ee.schimke.ha.rc.ui.HaToggleButton
import ee.schimke.ha.rc.ui.HaToggleSwitch
import ee.schimke.ha.rc.ui.HaUnsupported
import ee.schimke.ha.rc.ui.HaVerticalStack
import ee.schimke.ha.rc.ui.HaWeatherForecast
import kotlinx.serialization.json.JsonObject

/** The typed surface of a card composable taking one plain-data model: `Ha…(data, modifier)`. */
data class CardProps<D>(val data: D)

/** A card composable with a layout variant beside its model: `HaGauge(data, modifier, style)`. */
data class StyledCardProps<D, S>(val data: D, val style: S)

/**
 * A real Tier-2 card adapted directly: one `data` property whose object codec decodes into the
 * card's own `*UiData` model, the argument the registry passes to the actual composable.
 */
class CardAdapter<D>(id: String, record: ComponentRecord, codec: AdapterValueCodec<D>, default: D) :
  TypedComponentAdapter<CardProps<D>>(id, record) {
  val data = property(CardProps<D>::data, codec, default)
}

/** [CardAdapter] plus the composable's layout enum, published under its parameter name. */
class StyledCardAdapter<D, S>(
  id: String,
  record: ComponentRecord,
  codec: AdapterValueCodec<D>,
  default: D,
  styleName: String,
  styleCodec: AdapterValueCodec<S>,
  styleDefault: S,
) : TypedComponentAdapter<StyledCardProps<D, S>>(id, record) {
  val data = property(StyledCardProps<D, S>::data, codec, default)
  val style = property(StyledCardProps<D, S>::style, styleCodec, styleDefault, name = styleName)
}

/** The typed surface of [HaToggleSwitch], whose parameters are scalars, colours and an action. */
data class ToggleSwitchProps(
  val initiallyOn: Boolean,
  val activeAccent: Color,
  val inactiveAccent: Color,
  val tapAction: HaAction,
)

class ToggleSwitchAdapter(record: ComponentRecord) :
  TypedComponentAdapter<ToggleSwitchProps>("homeassistant-remotecompose/toggle-switch", record) {
  val initiallyOn = property(ToggleSwitchProps::initiallyOn, AdapterValueCodecs.Boolean, true)
  val activeAccent =
    property(ToggleSwitchProps::activeAccent, ColorCodec, HomeAssistantDefaults.Amber)
  val inactiveAccent =
    property(ToggleSwitchProps::inactiveAccent, ColorCodec, HomeAssistantDefaults.Grey)
  val tapAction =
    property(ToggleSwitchProps::tapAction, HaActionCodec, HaAction.Toggle("switch.kitchen"))
}

/** A metric exposes each editable part directly in the inspector. */
data class MetricProps(
  val label: String,
  val value: String,
  val unit: String,
  val valueColor: Color,
)

class MetricAdapter(record: ComponentRecord) :
  TypedComponentAdapter<MetricProps>("homeassistant-remotecompose/metric", record) {
  val label = property(MetricProps::label, AdapterValueCodecs.String, "Living room")
  val value = property(MetricProps::value, AdapterValueCodecs.String, "21.5")
  val unit = property(MetricProps::unit, AdapterValueCodecs.String, "°C")
  val valueColor = property(MetricProps::valueColor, ColorCodec, Color(0xFF141414))
}

/** A layout composable with one `content` slot: the vertical and horizontal stacks and the grid. */
class StackAdapter(id: String, record: ComponentRecord) : TypedComponentAdapter<Unit>(id, record) {
  val content = slot("content")
}

class HomeAssistantCatalog(records: ComponentRecordFile) {
  private val byName = records.components.associateBy { it.symbol.name }

  private fun record(name: String): ComponentRecord =
    requireNotNull(byName[name]) { "no discovered record for $name" }

  private fun <D> card(name: String, id: String, codec: AdapterValueCodec<D>, default: D) =
    CardAdapter("homeassistant-remotecompose/$id", record(name), codec, default)

  private fun <D, S> styled(
    name: String,
    id: String,
    codec: AdapterValueCodec<D>,
    default: D,
    styleName: String,
    styleCodec: AdapterValueCodec<S>,
    styleDefault: S,
  ) =
    StyledCardAdapter(
      "homeassistant-remotecompose/$id",
      record(name),
      codec,
      default,
      styleName,
      styleCodec,
      styleDefault,
    )

  private val d = HomeAssistantDefaults

  // Ids of the first, stand-in catalog are kept, so stored designs still find their component.
  val heading = card("HaHeading", "heading", HeadingCodec, d.heading)
  val tile = card("HaTile", "tile", TileCodec, d.tile)
  val button = card("HaButton", "button", ButtonCodec, d.button)
  val toggleButton = card("HaToggleButton", "toggle-button", ButtonCodec, d.button)
  val entityRow = card("HaEntityRow", "entity-row", EntityRowCodec, d.entityRow)
  val entities = card("HaEntities", "entities", EntitiesCodec, d.entities)
  val glance = card("HaGlance", "glance", GlanceCodec, d.glance)
  val glanceCell = card("HaGlanceCell", "glance-cell", GlanceCellCodec, d.glance.cells.first())
  val markdown = card("HaMarkdown", "markdown", MarkdownCodec, d.markdown)
  val unsupported = card("HaUnsupported", "unsupported", UnsupportedCodec, d.unsupported)
  val toggleSwitch = ToggleSwitchAdapter(record("HaToggleSwitch"))
  val metric = MetricAdapter(record("HaMetric"))
  val gauge =
    styled("HaGauge", "gauge", GaugeCodec, d.gauge, "style", GaugeStyleCodec, HaGaugeStyle.Standard)
  val arcDial =
    styled(
      "HaArcDial",
      "arc-dial",
      ArcDialCodec,
      d.arcDial,
      "style",
      ArcDialStyleCodec,
      HaArcDialStyle.Standard,
    )
  val historyGraph = card("HaHistoryGraph", "history-graph", HistoryGraphCodec, d.historyGraph)
  val statisticsGraph =
    card("HaStatisticsGraph", "statistics-graph", StatisticsGraphCodec, d.statisticsGraph)
  val statisticCard = card("HaStatisticCard", "statistic", StatisticCardCodec, d.statisticCard)
  val sensorCard = card("HaSensorCard", "sensor", SensorCardCodec, d.sensorCard)
  val logbook = card("HaLogbook", "logbook", LogbookCodec, d.logbook)
  val calendar = card("HaCalendar", "calendar", CalendarCodec, d.calendar)
  val todoList = card("HaTodoList", "todo-list", TodoListCodec, d.todoList)
  val weatherForecast =
    styled(
      "HaWeatherForecast",
      "weather-forecast",
      WeatherForecastCodec,
      d.weather,
      "layout",
      CardLayoutCodec,
      HaCardLayout.Standard,
    )
  val mediaControl =
    styled(
      "HaMediaControl",
      "media-control",
      MediaControlCodec,
      d.mediaControl,
      "layout",
      CardLayoutCodec,
      HaCardLayout.Standard,
    )
  val alarmPanel =
    styled(
      "HaAlarmPanel",
      "alarm-panel",
      AlarmPanelCodec,
      d.alarmPanel,
      "layout",
      CardLayoutCodec,
      HaCardLayout.Standard,
    )
  val area = card("HaArea", "area", AreaCodec, d.area)
  val clock = card("HaClock", "clock", ClockCodec, d.clock)
  val picture = card("HaPicture", "picture", PictureCodec, d.picture)
  val pictureGlance = card("HaPictureGlance", "picture-glance", PictureGlanceCodec, d.pictureGlance)
  val pictureElements =
    card("HaPictureElements", "picture-elements", PictureElementsCodec, d.pictureElements)
  val pictureEntity = card("HaPictureEntity", "picture-entity", PictureEntityCodec, d.pictureEntity)
  val bambuPrintStatus =
    card("HaBambuPrintStatus", "bambu-print-status", BambuPrintStatusCodec, d.bambuPrintStatus)
  val bambuPrintControl =
    card("HaBambuPrintControl", "bambu-print-control", BambuPrintControlCodec, d.bambuPrintControl)
  val bambuAms = card("HaBambuAms", "bambu-ams", BambuAmsCodec, d.bambuAms)
  val bambuSpool = card("HaBambuSpool", "bambu-spool", BambuSpoolDetailCodec, d.bambuSpool)
  val stack = StackAdapter("homeassistant-remotecompose/vertical-stack", record("HaVerticalStack"))
  val horizontalStack =
    StackAdapter("homeassistant-remotecompose/horizontal-stack", record("HaHorizontalStack"))
  val grid = StackAdapter("homeassistant-remotecompose/grid", record("HaGrid"))

  val definitions: List<TypedComponentAdapter<*>> =
    listOf(
      heading,
      tile,
      button,
      toggleButton,
      entityRow,
      entities,
      glance,
      glanceCell,
      markdown,
      unsupported,
      toggleSwitch,
      metric,
      gauge,
      arcDial,
      historyGraph,
      statisticsGraph,
      statisticCard,
      sensorCard,
      logbook,
      calendar,
      todoList,
      weatherForecast,
      mediaControl,
      alarmPanel,
      area,
      clock,
      picture,
      pictureGlance,
      pictureElements,
      pictureEntity,
      bambuPrintStatus,
      bambuPrintControl,
      bambuAms,
      bambuSpool,
      stack,
      horizontalStack,
      grid,
    )

  fun generate(records: ComponentRecordFile): GeneratedAdapterCatalog =
    TypedAdapterCatalog.generate(
      records,
      UiBuilderCatalogs.CoverSheet(
        "homeassistant-remotecompose",
        "Home Assistant native components",
      ),
      UiBuilderPolicyFile.Builder(UI_BUILDER_POLICY_SCHEMA, "mobile").build(),
      definitions,
    )

  fun registry(): CanvasAdapterRegistry = canvasAdapterRegistry {
    // Each Tier-2 card captures a Remote Compose document; a changed value starts a fresh capture.
    register(heading) {
      val data = legacyData(heading.data) { HomeAssistantLegacy.heading(it, properties()) }
      key(data) { HaHeading(data, modifier) }
    }
    register(tile) {
      val data = legacyData(tile.data) { HomeAssistantLegacy.tile(it, properties()) }
      key(data) { HaTile(data, modifier) }
    }
    register(button) {
      val data = legacyData(button.data) { HomeAssistantLegacy.button(it, properties()) }
      key(data) { HaButton(data, modifier) }
    }
    register(toggleButton) { card(toggleButton.data) { HaToggleButton(it, modifier) } }
    register(entityRow) {
      val data = legacyData(entityRow.data) { HomeAssistantLegacy.entityRow(it, properties()) }
      key(data) { HaEntityRow(data, modifier) }
    }
    register(entities) {
      val data = legacyData(entities.data) { HomeAssistantLegacy.entities(it, properties()) }
      key(data) { HaEntities(data, modifier) }
    }
    register(glance) {
      val data = legacyData(glance.data) { HomeAssistantLegacy.glance(it, properties()) }
      key(data) { HaGlance(data, modifier) }
    }
    register(glanceCell) { card(glanceCell.data) { HaGlanceCell(it, modifier) } }
    register(markdown) { card(markdown.data) { HaMarkdown(it, modifier) } }
    register(unsupported) { card(unsupported.data) { HaUnsupported(it, modifier) } }
    register(toggleSwitch) {
      val on = value(toggleSwitch.initiallyOn)
      val active = value(toggleSwitch.activeAccent)
      val inactive = value(toggleSwitch.inactiveAccent)
      val tap = value(toggleSwitch.tapAction)
      key(on, active, inactive, tap) {
        HaToggleSwitch(
          initiallyOn = on,
          activeAccent = active,
          inactiveAccent = inactive,
          modifier = modifier,
          tapAction = tap,
        )
      }
    }
    register(metric) {
      val label = value(metric.label)
      val reading = value(metric.value)
      val unit = value(metric.unit)
      val color = value(metric.valueColor)
      key(label, reading, unit, color) { HaMetric(label, reading, unit, color, modifier) }
    }
    register(gauge) { styled(gauge.data, gauge.style) { d, s -> HaGauge(d, modifier, s) } }
    register(arcDial) { styled(arcDial.data, arcDial.style) { d, s -> HaArcDial(d, modifier, s) } }
    register(historyGraph) { card(historyGraph.data) { HaHistoryGraph(it, modifier) } }
    register(statisticsGraph) { card(statisticsGraph.data) { HaStatisticsGraph(it, modifier) } }
    register(statisticCard) { card(statisticCard.data) { HaStatisticCard(it, modifier) } }
    register(sensorCard) { card(sensorCard.data) { HaSensorCard(it, modifier) } }
    register(logbook) { card(logbook.data) { HaLogbook(it, modifier) } }
    register(calendar) { card(calendar.data) { HaCalendar(it, modifier) } }
    register(todoList) { card(todoList.data) { HaTodoList(it, modifier) } }
    register(weatherForecast) {
      styled(weatherForecast.data, weatherForecast.style) { d, s ->
        HaWeatherForecast(d, modifier, layout = s)
      }
    }
    register(mediaControl) {
      styled(mediaControl.data, mediaControl.style) { d, s ->
        HaMediaControl(d, modifier, layout = s)
      }
    }
    register(alarmPanel) {
      styled(alarmPanel.data, alarmPanel.style) { d, s -> HaAlarmPanel(d, modifier, layout = s) }
    }
    register(area) { card(area.data) { HaArea(it, modifier) } }
    register(clock) { card(clock.data) { HaClock(it, modifier) } }
    register(picture) { card(picture.data) { HaPicture(it, modifier) } }
    register(pictureGlance) { card(pictureGlance.data) { HaPictureGlance(it, modifier) } }
    register(pictureElements) { card(pictureElements.data) { HaPictureElements(it, modifier) } }
    register(pictureEntity) { card(pictureEntity.data) { HaPictureEntity(it, modifier) } }
    register(bambuPrintStatus) { card(bambuPrintStatus.data) { HaBambuPrintStatus(it, modifier) } }
    register(bambuPrintControl) {
      card(bambuPrintControl.data) { HaBambuPrintControl(it, modifier) }
    }
    register(bambuAms) { card(bambuAms.data) { HaBambuAms(it, modifier) } }
    register(bambuSpool) { card(bambuSpool.data) { HaBambuSpool(it, modifier) } }
    register(stack) { HaVerticalStack(modifier = modifier) { Slot(stack.content) } }
    register(horizontalStack) {
      HaHorizontalStack(modifier = modifier) { Slot(horizontalStack.content) }
    }
    register(grid) { HaGrid(modifier = modifier) { Slot(grid.content) } }
  }
}

/** Decodes [property] and draws [content] with it, keyed so an edit recaptures the document. */
@Composable
private fun <P, D> TypedCanvasNodeScope<P>.card(
  property: AdapterProperty<P, D>,
  content: @Composable (D) -> Unit,
) {
  val data = value(property)
  key(data) { content(data) }
}

/** [card] for a card with a layout variant. */
@Composable
private fun <P, D, S> TypedCanvasNodeScope<P>.styled(
  data: AdapterProperty<P, D>,
  style: AdapterProperty<P, S>,
  content: @Composable (D, S) -> Unit,
) {
  val d = value(data)
  val s = value(style)
  key(d, s) { content(d, s) }
}

private fun TypedCanvasNodeScope<*>.properties(): JsonObject = canvas.node.properties

/**
 * [property]'s value; a node saved before the catalog adapted the real model has no `data`, so its
 * first-catalog flat properties are read over the default by [legacy] instead.
 */
private fun <P, D> TypedCanvasNodeScope<P>.legacyData(
  property: AdapterProperty<P, D>,
  legacy: (D) -> D,
): D = if (property.name in canvas.node.properties) value(property) else legacy(value(property))

/**
 * Reads designs stored against the first, stand-in catalog, whose cards took flat `name` / `state`
 * / `title` / `showName` literals and `CatalogEntity(name, state)` lists. Only literals are read; a
 * value that is absent or not a literal of the old type keeps the default's.
 */
internal object HomeAssistantLegacy {
  /** The first catalog's `CatalogEntity(name, state)` list element. */
  private data class Entity(val name: String, val state: String)

  private val entityList =
    AdapterValueCodecs.list(
      AdapterValueCodecs.obj<Entity>("ee.schimke.ha.catalog.CatalogEntity") {
        val name = field(Entity::name, AdapterValueCodecs.String)
        val state = field(Entity::state, AdapterValueCodecs.String, default = "")
        construct { Entity(it[name], it[state]) }
      }
    )

  private fun <T> read(properties: JsonObject, name: String, codec: AdapterValueCodec<T>): T? =
    (properties[name] as? JsonObject)?.let { runCatching { codec.unwrap(it) }.getOrNull() }

  private fun text(p: JsonObject, name: String) = read(p, name, AdapterValueCodecs.String)

  /** The old list cards' title: an empty one meant no header. */
  private fun title(p: JsonObject, default: String?) =
    text(p, "title")?.let { it.ifEmpty { null } } ?: default.takeUnless { "title" in p }

  fun heading(default: HaHeadingUiData, p: JsonObject) =
    default.copy(title = text(p, "title") ?: default.title)

  fun tile(default: HaTileUiData, p: JsonObject) =
    default.copy(name = text(p, "name") ?: default.name, state = text(p, "state") ?: default.state)

  fun button(default: HaButtonUiData, p: JsonObject) =
    default.copy(
      name = text(p, "name") ?: default.name,
      showName = read(p, "showName", AdapterValueCodecs.Boolean) ?: default.showName,
    )

  fun entityRow(default: HaEntityRowUiData, p: JsonObject) =
    default.copy(name = text(p, "name") ?: default.name, state = text(p, "state") ?: default.state)

  fun entities(default: HaEntitiesUiData, p: JsonObject) =
    default.copy(
      title = title(p, default.title),
      rows =
        read(p, "rows", entityList)?.map {
          default.rows.first().copy(name = it.name, state = it.state)
        } ?: default.rows,
    )

  fun glance(default: HaGlanceUiData, p: JsonObject) =
    default.copy(
      title = title(p, default.title),
      cells =
        read(p, "cells", entityList)?.map {
          default.cells.first().copy(name = it.name, state = it.state)
        } ?: default.cells,
    )
}
