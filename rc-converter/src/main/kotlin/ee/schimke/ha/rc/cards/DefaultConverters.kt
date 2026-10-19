package ee.schimke.ha.rc.cards

import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardRegistry

/**
 * Built-in converters that ship with the library.
 *
 * **Real converters**, one per card type: tile, button, entity, entities, glance, heading,
 * markdown, vertical-stack, horizontal-stack, grid, conditional, map, history-graph, gauge,
 * picture-entity, weather-forecast, logbook, thermostat, humidifier, light, clock,
 * statistics-graph, alarm-panel, media-control, todo-list, calendar, area, picture, picture-glance,
 * picture-elements, statistic, sensor, entity-filter, and the `custom:ha-bambulab-*` cards (AMS,
 * spool, print status, print control, skip object). `map` draws its tracked entities on a flat
 * chrome rather than map tiles, which a `.rc` document can't carry.
 *
 * **Placeholder-only** ([PLACEHOLDER_CARD_TYPES], an [UnsupportedCardConverter] with a "not yet
 * supported" badge so the dashboard keeps the slot): iframe. Any other type falls back to the
 * registry's catch-all [UnsupportedCardConverter].
 */
fun defaultConverters(): List<CardConverter> = buildList {
  add(MessageCardConverter())
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
  add(AlarmPanelCardConverter())
  add(MediaControlCardConverter())
  add(TodoListCardConverter())
  add(CalendarCardConverter())
  add(AreaCardConverter())
  add(PictureCardConverter())
  add(PictureGlanceCardConverter())
  add(PictureElementsCardConverter())
  add(StatisticCardConverter())
  add(SensorCardConverter())
  add(EntityFilterCardConverter())

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

private val PLACEHOLDER_CARD_TYPES: List<String> = listOf(CardTypes.IFRAME)
