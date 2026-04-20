package ee.schimke.ha.model

/**
 * Canonical identifiers for built-in Lovelace card types. Custom cards
 * arrive as `"custom:<name>"` and are not enumerated here.
 *
 * Converters register themselves by string id; this object is a typo-check
 * aid, not a schema constraint.
 */
object CardTypes {
    const val ENTITIES = "entities"
    const val GLANCE = "glance"
    const val TILE = "tile"
    const val BUTTON = "button"
    const val ENTITY = "entity"
    const val GAUGE = "gauge"
    const val SENSOR = "sensor"
    const val HISTORY_GRAPH = "history-graph"
    const val STATISTICS_GRAPH = "statistics-graph"
    const val STATISTIC = "statistic"
    const val MAP = "map"
    const val PICTURE = "picture"
    const val PICTURE_ENTITY = "picture-entity"
    const val PICTURE_GLANCE = "picture-glance"
    const val PICTURE_ELEMENTS = "picture-elements"
    const val MARKDOWN = "markdown"
    const val IFRAME = "iframe"
    const val WEATHER_FORECAST = "weather-forecast"
    const val THERMOSTAT = "thermostat"
    const val HUMIDIFIER = "humidifier"
    const val LIGHT = "light"
    const val MEDIA_CONTROL = "media-control"
    const val ALARM_PANEL = "alarm-panel"
    const val AREA = "area"
    const val CALENDAR = "calendar"
    const val LOGBOOK = "logbook"
    const val CLOCK = "clock"
    const val TODO_LIST = "todo-list"
    const val CONDITIONAL = "conditional"
    const val ENTITY_FILTER = "entity-filter"
    const val VERTICAL_STACK = "vertical-stack"
    const val HORIZONTAL_STACK = "horizontal-stack"
    const val GRID = "grid"
    const val HEADING = "heading"
}
