package ee.schimke.ha.rc.cards

import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardRegistry

/**
 * Built-in converters that ship with the library.
 *
 * **Fully implemented** (produce visually-correct output):
 *   tile, button, entity, entities, glance, heading, markdown,
 *   vertical-stack, horizontal-stack, grid.
 *
 * **Placeholder-only** (registered so the dashboard renders, but with a
 * "not yet supported" badge — extend by writing a `RemoteHa…` composable
 * + converter shim):
 *   gauge, thermostat, humidifier, light, media-control, alarm-panel,
 *   weather-forecast, clock, area, calendar, logbook, todo-list, map,
 *   picture, picture-entity, picture-glance, picture-elements,
 *   history-graph, statistics-graph, statistic, sensor, conditional,
 *   entity-filter, iframe.
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

    PLACEHOLDER_CARD_TYPES.forEach { add(UnsupportedCardConverter(it)) }

    // Registry fallback for types we didn't list explicitly.
    add(UnsupportedCardConverter())
}

fun defaultRegistry(): CardRegistry = CardRegistry(defaultConverters())

private val PLACEHOLDER_CARD_TYPES: List<String> = listOf(
    CardTypes.GAUGE,
    CardTypes.THERMOSTAT,
    CardTypes.HUMIDIFIER,
    CardTypes.LIGHT,
    CardTypes.MEDIA_CONTROL,
    CardTypes.ALARM_PANEL,
    CardTypes.WEATHER_FORECAST,
    CardTypes.CLOCK,
    CardTypes.AREA,
    CardTypes.CALENDAR,
    CardTypes.LOGBOOK,
    CardTypes.TODO_LIST,
    CardTypes.MAP,
    CardTypes.PICTURE,
    CardTypes.PICTURE_ENTITY,
    CardTypes.PICTURE_GLANCE,
    CardTypes.PICTURE_ELEMENTS,
    CardTypes.HISTORY_GRAPH,
    CardTypes.STATISTICS_GRAPH,
    CardTypes.STATISTIC,
    CardTypes.SENSOR,
    CardTypes.CONDITIONAL,
    CardTypes.ENTITY_FILTER,
    CardTypes.IFRAME,
)
