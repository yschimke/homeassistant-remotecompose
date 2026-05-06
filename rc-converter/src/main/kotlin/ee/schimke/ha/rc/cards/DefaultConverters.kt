package ee.schimke.ha.rc.cards

import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardRegistry

/**
 * Built-in converters that ship with the library.
 *
 * **Fully implemented** (produce visually-correct output):
 *   tile, button, entity, entities, glance, heading, markdown,
 *   vertical-stack, horizontal-stack, grid, conditional, gauge,
 *   weather-forecast, picture-entity, logbook.
 *
 * **Summary chrome** (registered with a real converter that renders
 * a card chrome but skips the rich visualisation — replace with the
 * full `RemoteHa…` composable when ready):
 *   map, history-graph, custom:ha-bambulab-*.
 *
 * **Placeholder-only** (registered so the dashboard renders, but with a
 * "not yet supported" badge — extend by writing a `RemoteHa…` composable
 * + converter shim):
 *   thermostat, humidifier, light, media-control, alarm-panel,
 *   clock, area, calendar, todo-list,
 *   picture, picture-glance, picture-elements,
 *   statistics-graph, statistic, sensor, entity-filter, iframe.
 */
fun defaultConverters(): List<CardConverter> = buildList {
    add(TileCardConverter())
    add(ButtonCardConverter())
    add(EntityCardConverter())
    add(EntitiesCardConverter())
    add(GlanceCardConverter())
    add(HeadingCardConverter())
    add(MarkdownCardConverter())
    add(VerticalStackCardConverter())
    add(HorizontalStackCardConverter())
    add(GridCardConverter())
    add(ConditionalCardConverter())
    add(MapCardConverter())
    add(HistoryGraphCardConverter())
    add(GaugeCardConverter())
    add(PictureEntityCardConverter())
    add(WeatherForecastCardConverter())
    add(LogbookCardConverter())
    add(ThermostatCardConverter())
    add(HumidifierCardConverter())
    add(LightCardConverter())
    add(ClockCardConverter())
    add(StatisticsGraphCardConverter())

    add(BambuLabAmsCardConverter())
    add(BambuLabSpoolCardConverter())
    add(BambuLabPrintStatusCardConverter())
    add(BambuLabPrintControlCardConverter())
    add(BambuLabSkipObjectCardConverter())

    PLACEHOLDER_CARD_TYPES.forEach { add(UnsupportedCardConverter(it)) }

    // Registry fallback for types we didn't list explicitly.
    add(UnsupportedCardConverter())
}

fun defaultRegistry(): CardRegistry = CardRegistry(defaultConverters())

private val PLACEHOLDER_CARD_TYPES: List<String> = listOf(
    CardTypes.MEDIA_CONTROL,
    CardTypes.ALARM_PANEL,
    CardTypes.AREA,
    CardTypes.CALENDAR,
    CardTypes.TODO_LIST,
    CardTypes.PICTURE,
    CardTypes.PICTURE_GLANCE,
    CardTypes.PICTURE_ELEMENTS,
    CardTypes.STATISTIC,
    CardTypes.SENSOR,
    CardTypes.ENTITY_FILTER,
    CardTypes.IFRAME,
)
