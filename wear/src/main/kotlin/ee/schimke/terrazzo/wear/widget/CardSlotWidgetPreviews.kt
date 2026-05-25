@file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

package ee.schimke.terrazzo.wear.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.FixedHaClock
import ee.schimke.ha.rc.LocalHaClock
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * One @Preview entry per card template, grouped by data tier (see
 * `docs/architecture/adaptive-card-layouts.md` § "Wear data-layer
 * reality" and [WearCardDataTier]):
 *
 *   - **Small-only.** Card has hardly any live data on Wear — either
 *     because its config is static (`heading`, `markdown`, `clock`) or
 *     because its identity needs P4 / P5 payloads the wear sync proto
 *     doesn't carry (`weather-forecast`, `logbook`, `history-graph`,
 *     `statistics-graph`, `todo-list`, `calendar`). At the large
 *     container these would render as their stripped tier with a lot
 *     of empty space, so we don't advertise large.
 *   - **Single-entity (small + large, shared fixture).** Card carries
 *     one entity's P1 + P2 + P3 at any size; the renderer's own ladder
 *     picks the right tier. Same fixture feeds both previews.
 *   - **Multi-entity (small + large, separate fixtures).** Card's P5
 *     is a list of additional entities. Small fixture is stripped to
 *     one entity (no title) so the preview shows the collapsed tier;
 *     large fixture carries the full multi-entity payload so the
 *     preview shows the filled tier.
 *
 * Each preview runs the card through the wear capture path
 * ([SlotWidgetPreviewFixture]) so the rendered output matches what the
 * watch will actually draw — including the `ProvideCardChrome(false)`
 * suppression that strips each card's own rounded-clip + divider
 * border, since the Glance Wear container already supplies the slot's
 * shape and brush.
 *
 * Card configs are kept minimal — just enough to exercise the rendered
 * layout. Snapshots use the same shape as runtime values flowing over
 * the wear sync proto (state + friendly_name + unit + device_class).
 *
 * For the cross-surface "how does this card look at each scale" view
 * (app preferred + launcher widget sizes alongside wear), see
 * `ee.schimke.ha.previews.CardPreviewMatrix` in the previews module.
 */

// ── Small-only previews ─────────────────────────────────────────────
// Low-data card types. See WearCardDataTier.SmallOnly.

@Preview(name = "wear · heading (small)")
@Composable
fun WearHeadingSmall() = wearCardPreview(headingFixture(), ContainerType.Small)

@Preview(name = "wear · markdown (small)")
@Composable
fun WearMarkdownSmall() = wearCardPreview(markdownFixture(), ContainerType.Small)

/**
 * Clock card normally binds `RemoteTimeDefaults.defaultTimeString` so
 * the player ticks the time without a re-encode — at preview capture
 * that resolves to wall-clock time and the PNG drifts each minute.
 * Inject a [previewNow] and surface it via [LocalHaClock] as a
 * [FixedHaClock] so [ClockCardConverter] takes its static-label path;
 * the default matches the frozen "now" used by the other preview
 * entry points.
 */
@Preview(name = "wear · clock (small)")
@Composable
fun WearClockSmall(previewNow: ZonedDateTime = WearPreviewNow) {
    CompositionLocalProvider(LocalHaClock provides FixedHaClock(previewNow)) {
        wearCardPreview(clockFixture(), ContainerType.Small)
    }
}

private val WearPreviewNow: ZonedDateTime =
    ZonedDateTime.of(2026, 5, 5, 10, 8, 0, 0, ZoneOffset.UTC)

@Preview(name = "wear · weather-forecast (small)")
@Composable
fun WearWeatherForecastSmall() =
    wearCardPreview(weatherForecastFixture(), ContainerType.Small)

@Preview(name = "wear · logbook (small)")
@Composable
fun WearLogbookSmall() = wearCardPreview(logbookFixture(), ContainerType.Small)

@Preview(name = "wear · history-graph (small)")
@Composable
fun WearHistoryGraphSmall() = wearCardPreview(historyGraphFixture(), ContainerType.Small)

@Preview(name = "wear · statistics-graph (small)")
@Composable
fun WearStatisticsGraphSmall() =
    wearCardPreview(statisticsGraphFixture(), ContainerType.Small)

@Preview(name = "wear · todo-list (small)")
@Composable
fun WearTodoListSmall() = wearCardPreview(todoListFixture(), ContainerType.Small)

@Preview(name = "wear · calendar (small)")
@Composable
fun WearCalendarSmall() = wearCardPreview(calendarFixture(), ContainerType.Small)

// ── Single-entity previews (shared fixture, small + large) ──────────
// One entity's worth of P1 + P2 + P3 at any canvas size. The
// renderer's per-card ladder picks the right tier.

@Preview(name = "wear · tile (small)")
@Composable
fun WearTileSmall() = wearCardPreview(tileFixture(), ContainerType.Small)

@Preview(name = "wear · tile (large)")
@Composable
fun WearTileLarge() = wearCardPreview(tileFixture(), ContainerType.Large)

@Preview(name = "wear · button (small)")
@Composable
fun WearButtonSmall() = wearCardPreview(buttonFixture(), ContainerType.Small)

@Preview(name = "wear · button (large)")
@Composable
fun WearButtonLarge() = wearCardPreview(buttonFixture(), ContainerType.Large)

@Preview(name = "wear · entity (small)")
@Composable
fun WearEntitySmall() = wearCardPreview(entityFixture(), ContainerType.Small)

@Preview(name = "wear · entity (large)")
@Composable
fun WearEntityLarge() = wearCardPreview(entityFixture(), ContainerType.Large)

@Preview(name = "wear · gauge (small)")
@Composable
fun WearGaugeSmall() = wearCardPreview(gaugeFixture(), ContainerType.Small)

@Preview(name = "wear · gauge (large)")
@Composable
fun WearGaugeLarge() = wearCardPreview(gaugeFixture(), ContainerType.Large)

@Preview(name = "wear · light (small)")
@Composable
fun WearLightSmall() = wearCardPreview(lightFixture(), ContainerType.Small)

@Preview(name = "wear · light (large)")
@Composable
fun WearLightLarge() = wearCardPreview(lightFixture(), ContainerType.Large)

@Preview(name = "wear · picture-entity (small)")
@Composable
fun WearPictureEntitySmall() =
    wearCardPreview(pictureEntityFixture(), ContainerType.Small)

@Preview(name = "wear · picture-entity (large)")
@Composable
fun WearPictureEntityLarge() =
    wearCardPreview(pictureEntityFixture(), ContainerType.Large)

@Preview(name = "wear · picture (small)")
@Composable
fun WearPictureSmall() = wearCardPreview(pictureFixture(), ContainerType.Small)

@Preview(name = "wear · picture (large)")
@Composable
fun WearPictureLarge() = wearCardPreview(pictureFixture(), ContainerType.Large)

@Preview(name = "wear · sensor (small)")
@Composable
fun WearSensorSmall() = wearCardPreview(sensorFixture(), ContainerType.Small)

@Preview(name = "wear · sensor (large)")
@Composable
fun WearSensorLarge() = wearCardPreview(sensorFixture(), ContainerType.Large)

@Preview(name = "wear · statistic (small)")
@Composable
fun WearStatisticSmall() = wearCardPreview(statisticFixture(), ContainerType.Small)

@Preview(name = "wear · statistic (large)")
@Composable
fun WearStatisticLarge() = wearCardPreview(statisticFixture(), ContainerType.Large)

@Preview(name = "wear · thermostat (small)")
@Composable
fun WearThermostatSmall() = wearCardPreview(thermostatFixture(), ContainerType.Small)

@Preview(name = "wear · thermostat (large)")
@Composable
fun WearThermostatLarge() = wearCardPreview(thermostatFixture(), ContainerType.Large)

@Preview(name = "wear · humidifier (small)")
@Composable
fun WearHumidifierSmall() = wearCardPreview(humidifierFixture(), ContainerType.Small)

@Preview(name = "wear · humidifier (large)")
@Composable
fun WearHumidifierLarge() = wearCardPreview(humidifierFixture(), ContainerType.Large)

@Preview(name = "wear · alarm-panel (small)")
@Composable
fun WearAlarmPanelSmall() = wearCardPreview(alarmPanelFixture(), ContainerType.Small)

@Preview(name = "wear · alarm-panel (large)")
@Composable
fun WearAlarmPanelLarge() = wearCardPreview(alarmPanelFixture(), ContainerType.Large)

@Preview(name = "wear · media-control (small)")
@Composable
fun WearMediaControlSmall() =
    wearCardPreview(mediaControlFixture(), ContainerType.Small)

@Preview(name = "wear · media-control (large)")
@Composable
fun WearMediaControlLarge() =
    wearCardPreview(mediaControlFixture(), ContainerType.Large)

@Preview(name = "wear · bambu spool (small)")
@Composable
fun WearBambuSpoolSmall() = wearCardPreview(bambuSpoolFixture(), ContainerType.Small)

@Preview(name = "wear · bambu spool (large)")
@Composable
fun WearBambuSpoolLarge() = wearCardPreview(bambuSpoolFixture(), ContainerType.Large)

@Preview(name = "wear · unsupported (small)")
@Composable
fun WearUnsupportedSmall() = wearCardPreview(unsupportedFixture(), ContainerType.Small)

@Preview(name = "wear · unsupported (large)")
@Composable
fun WearUnsupportedLarge() = wearCardPreview(unsupportedFixture(), ContainerType.Large)

// ── Multi-entity previews (stripped small + filled large) ───────────
// P5 is a list of additional entities — the small fixture is stripped
// to one row / cell so the preview shows the collapsed tier; the
// large fixture carries the full multi-entity payload so the preview
// shows the filled tier.

@Preview(name = "wear · entities (small)")
@Composable
fun WearEntitiesSmall() =
    wearCardPreview(entitiesSmallFixture(), ContainerType.Small)

@Preview(name = "wear · entities (large)")
@Composable
fun WearEntitiesLarge() =
    wearCardPreview(entitiesLargeFixture(), ContainerType.Large)

@Preview(name = "wear · glance (small)")
@Composable
fun WearGlanceSmall() = wearCardPreview(glanceSmallFixture(), ContainerType.Small)

@Preview(name = "wear · glance (large)")
@Composable
fun WearGlanceLarge() = wearCardPreview(glanceLargeFixture(), ContainerType.Large)

@Preview(name = "wear · area (small)")
@Composable
fun WearAreaSmall() = wearCardPreview(areaSmallFixture(), ContainerType.Small)

@Preview(name = "wear · area (large)")
@Composable
fun WearAreaLarge() = wearCardPreview(areaLargeFixture(), ContainerType.Large)

@Preview(name = "wear · picture-glance (small)")
@Composable
fun WearPictureGlanceSmall() =
    wearCardPreview(pictureGlanceSmallFixture(), ContainerType.Small)

@Preview(name = "wear · picture-glance (large)")
@Composable
fun WearPictureGlanceLarge() =
    wearCardPreview(pictureGlanceLargeFixture(), ContainerType.Large)

@Preview(name = "wear · picture-elements (small)")
@Composable
fun WearPictureElementsSmall() =
    wearCardPreview(pictureElementsSmallFixture(), ContainerType.Small)

@Preview(name = "wear · picture-elements (large)")
@Composable
fun WearPictureElementsLarge() =
    wearCardPreview(pictureElementsLargeFixture(), ContainerType.Large)

@Preview(name = "wear · entity-filter (small)")
@Composable
fun WearEntityFilterSmall() =
    wearCardPreview(entityFilterSmallFixture(), ContainerType.Small)

@Preview(name = "wear · entity-filter (large)")
@Composable
fun WearEntityFilterLarge() =
    wearCardPreview(entityFilterLargeFixture(), ContainerType.Large)

@Preview(name = "wear · vertical-stack (small)")
@Composable
fun WearVerticalStackSmall() =
    wearCardPreview(verticalStackSmallFixture(), ContainerType.Small)

@Preview(name = "wear · vertical-stack (large)")
@Composable
fun WearVerticalStackLarge() =
    wearCardPreview(verticalStackLargeFixture(), ContainerType.Large)

@Preview(name = "wear · horizontal-stack (small)")
@Composable
fun WearHorizontalStackSmall() =
    wearCardPreview(horizontalStackSmallFixture(), ContainerType.Small)

@Preview(name = "wear · horizontal-stack (large)")
@Composable
fun WearHorizontalStackLarge() =
    wearCardPreview(horizontalStackLargeFixture(), ContainerType.Large)

@Preview(name = "wear · grid (small)")
@Composable
fun WearGridSmall() = wearCardPreview(gridSmallFixture(), ContainerType.Small)

@Preview(name = "wear · grid (large)")
@Composable
fun WearGridLarge() = wearCardPreview(gridLargeFixture(), ContainerType.Large)

@Preview(name = "wear · bambu print-status (small)")
@Composable
fun WearBambuPrintStatusSmall() =
    wearCardPreview(bambuPrintStatusFixture(), ContainerType.Small)

@Preview(name = "wear · bambu print-status (large)")
@Composable
fun WearBambuPrintStatusLarge() =
    wearCardPreview(bambuPrintStatusFixture(), ContainerType.Large)

@Preview(name = "wear · bambu print-control (small)")
@Composable
fun WearBambuPrintControlSmall() =
    wearCardPreview(bambuPrintControlFixture(), ContainerType.Small)

@Preview(name = "wear · bambu print-control (large)")
@Composable
fun WearBambuPrintControlLarge() =
    wearCardPreview(bambuPrintControlFixture(), ContainerType.Large)

@Preview(name = "wear · bambu ams (small)")
@Composable
fun WearBambuAmsSmall() = wearCardPreview(bambuAmsSmallFixture(), ContainerType.Small)

@Preview(name = "wear · bambu ams (large)")
@Composable
fun WearBambuAmsLarge() = wearCardPreview(bambuAmsLargeFixture(), ContainerType.Large)

// ── helpers ──────────────────────────────────────────────────────────

private data class CardFixture(val json: String, val snapshot: HaSnapshot)

@Composable
private fun wearCardPreview(fixture: CardFixture, container: ContainerType) {
    SlotWidgetPreviewFixture(
        card = cardFromJson(fixture.json),
        snapshot = fixture.snapshot,
        container = container,
    )
}

// ── single-entity fixtures ──────────────────────────────────────────
//
// Each fixture pairs a card-config JSON snippet with the matching
// entity snapshot so the preview can resolve live bindings. Mirrors
// the canonical shapes in ee.schimke.ha.previews.CardPreviews /
// Fixtures — the wear previews intentionally bake their own copies to
// keep the wear module free of a previews-module dependency.

private fun tileFixture() = CardFixture(
    json = """{"type":"tile","entity":"sensor.living_room","name":"Living Room"}""",
    snapshot = snapshotOf(livingRoomTempState()),
)

private fun buttonFixture() = CardFixture(
    json = """{"type":"button","entity":"light.kitchen","name":"Kitchen","show_name":true}""",
    snapshot = snapshotOf(kitchenLightState()),
)

private fun entityFixture() = CardFixture(
    json = """{"type":"entity","entity":"sensor.living_room"}""",
    snapshot = snapshotOf(livingRoomTempState()),
)

private fun gaugeFixture() = CardFixture(
    json = """{"type":"gauge","entity":"sensor.battery","name":"Battery","min":0,"max":100,"needle":true}""",
    snapshot = snapshotOf(
        entityState(
            id = "sensor.battery",
            state = "72",
            friendlyName = "Battery",
            unit = "%",
            deviceClass = "battery",
        ),
    ),
)

private fun lightFixture() = CardFixture(
    json = """{"type":"light","entity":"light.kitchen"}""",
    snapshot = snapshotOf(kitchenLightState()),
)

private fun pictureEntityFixture() = CardFixture(
    json = """{"type":"picture-entity","entity":"camera.driveway","name":"Driveway","show_name":true}""",
    snapshot = snapshotOf(
        entityState(id = "camera.driveway", state = "idle", friendlyName = "Driveway"),
    ),
)

private fun pictureFixture() = CardFixture(
    json = """{"type":"picture","image":"/local/floorplan.png","name":"Floor plan"}""",
    snapshot = HaSnapshot(),
)

private fun sensorFixture() = CardFixture(
    json = """{"type":"sensor","entity":"sensor.outside_temp","hours_to_show":24}""",
    snapshot = snapshotOf(outsideTempState()),
)

private fun statisticFixture() = CardFixture(
    json = """{"type":"statistic","entity":"sensor.house_power","stat_type":"mean","period":"day"}""",
    snapshot = snapshotOf(housePowerState()),
)

private fun thermostatFixture() = CardFixture(
    json = """{"type":"thermostat","entity":"climate.living_room"}""",
    snapshot = snapshotOf(
        entityState(id = "climate.living_room", state = "heat", friendlyName = "Living Room"),
    ),
)

private fun humidifierFixture() = CardFixture(
    json = """{"type":"humidifier","entity":"humidifier.bedroom"}""",
    snapshot = snapshotOf(
        entityState(id = "humidifier.bedroom", state = "on", friendlyName = "Bedroom"),
    ),
)

private fun alarmPanelFixture() = CardFixture(
    json = """{"type":"alarm-panel","entity":"alarm_control_panel.house","states":["arm_away","arm_home"]}""",
    snapshot = snapshotOf(
        entityState(
            id = "alarm_control_panel.house",
            state = "disarmed",
            friendlyName = "House",
        ),
    ),
)

private fun mediaControlFixture() = CardFixture(
    json = """{"type":"media-control","entity":"media_player.office_speaker"}""",
    snapshot = snapshotOf(
        entityState(
            id = "media_player.office_speaker",
            state = "playing",
            friendlyName = "Office speaker",
        ),
    ),
)

private fun bambuSpoolFixture() = CardFixture(
    json = """{"type":"custom:ha-bambulab-spool-card","spool":"<spool-id-placeholder>"}""",
    snapshot = HaSnapshot(),
)

private fun unsupportedFixture() = CardFixture(
    json = """{"type":"iframe","url":"https://example.com"}""",
    snapshot = HaSnapshot(),
)

// ── small-only fixtures ─────────────────────────────────────────────
//
// These cards have hardly any live data on Wear. Their fixtures
// reflect what the watch will actually see — no forecast arrays, no
// list items, no event payloads — so the rendered preview matches
// the production stripped tier rather than the full HA-dashboard
// rendering.

private fun headingFixture() = CardFixture(
    json = """{"type":"heading","heading":"Downstairs"}""",
    snapshot = HaSnapshot(),
)

private fun markdownFixture() = CardFixture(
    json = """{"type":"markdown","title":"Notes","content":"Welcome home."}""",
    snapshot = HaSnapshot(),
)

private fun clockFixture() = CardFixture(
    json = """{"type":"clock","clock_size":"medium","time_format":"24"}""",
    snapshot = HaSnapshot(),
)

private fun weatherForecastFixture() = CardFixture(
    json = """{"type":"weather-forecast","entity":"weather.forecast_home","show_current":true,"show_forecast":false}""",
    snapshot = snapshotOf(
        entityState(
            id = "weather.forecast_home",
            state = "partlycloudy",
            friendlyName = "Forecast",
        ),
    ),
)

private fun logbookFixture() = CardFixture(
    json = """{"type":"logbook","title":"Recent","hours_to_show":24,"entities":["binary_sensor.front_door"]}""",
    snapshot = snapshotOf(
        entityState(id = "binary_sensor.front_door", state = "off", friendlyName = "Front door"),
    ),
)

private fun historyGraphFixture() = CardFixture(
    json = """{"type":"history-graph","title":"Temperature","hours_to_show":24,"entities":["sensor.outside_temp"]}""",
    snapshot = snapshotOf(outsideTempState()),
)

private fun statisticsGraphFixture() = CardFixture(
    json = """{"type":"statistics-graph","title":"Power","period":"hour","stat_types":["mean"],"entities":["sensor.house_power"]}""",
    snapshot = snapshotOf(housePowerState()),
)

private fun todoListFixture() = CardFixture(
    json = """{"type":"todo-list","entity":"todo.shopping","title":"Shopping"}""",
    snapshot = snapshotOf(
        entityState(id = "todo.shopping", state = "2", friendlyName = "Shopping"),
    ),
)

private fun calendarFixture() = CardFixture(
    json = """{"type":"calendar","title":"Family","initial_view":"listWeek","entities":["calendar.family"]}""",
    snapshot = snapshotOf(
        entityState(id = "calendar.family", state = "on", friendlyName = "Family"),
    ),
)

// ── multi-entity fixtures ───────────────────────────────────────────
//
// Paired Small / Large versions. The Small fixture is deliberately
// stripped (one entity, no title) so the preview shows the collapsed
// row; the Large fixture carries the full multi-entity payload so the
// preview shows the filled tier.

private fun entitiesSmallFixture() = CardFixture(
    json = """{"type":"entities","entities":["sensor.living_room"]}""",
    snapshot = snapshotOf(livingRoomTempState()),
)

private fun entitiesLargeFixture() = CardFixture(
    json = """{"type":"entities","title":"Living Room","entities":["sensor.living_room","light.kitchen","switch.coffee_maker","lock.front_door"]}""",
    snapshot = snapshotOf(
        livingRoomTempState(),
        kitchenLightState(),
        entityState(id = "switch.coffee_maker", state = "on", friendlyName = "Coffee maker"),
        entityState(id = "lock.front_door", state = "locked", friendlyName = "Front door"),
    ),
)

private fun glanceSmallFixture() = CardFixture(
    json = """{"type":"glance","entities":["light.kitchen"]}""",
    snapshot = snapshotOf(kitchenLightState()),
)

private fun glanceLargeFixture() = CardFixture(
    json = """{"type":"glance","title":"Overview","entities":["sensor.living_room","light.kitchen","lock.front_door","switch.coffee_maker"]}""",
    snapshot = snapshotOf(
        livingRoomTempState(),
        kitchenLightState(),
        entityState(id = "lock.front_door", state = "locked", friendlyName = "Front door"),
        entityState(id = "switch.coffee_maker", state = "on", friendlyName = "Coffee maker"),
    ),
)

private fun areaSmallFixture() = CardFixture(
    json = """{"type":"area","name":"Living room","entities":["sensor.living_room"]}""",
    snapshot = snapshotOf(livingRoomTempState()),
)

private fun areaLargeFixture() = CardFixture(
    json = """{"type":"area","name":"Living room","entities":["sensor.living_room","light.kitchen","switch.coffee_maker","lock.front_door"]}""",
    snapshot = snapshotOf(
        livingRoomTempState(),
        kitchenLightState(),
        entityState(id = "switch.coffee_maker", state = "on", friendlyName = "Coffee maker"),
        entityState(id = "lock.front_door", state = "locked", friendlyName = "Front door"),
    ),
)

private fun pictureGlanceSmallFixture() = CardFixture(
    json = """{"type":"picture-glance","image":"/local/lr.png","entities":["light.kitchen"]}""",
    snapshot = snapshotOf(kitchenLightState()),
)

private fun pictureGlanceLargeFixture() = CardFixture(
    json = """{"type":"picture-glance","title":"Living room","image":"/local/lr.png","entities":["light.kitchen","switch.coffee_maker","lock.front_door"]}""",
    snapshot = snapshotOf(
        kitchenLightState(),
        entityState(id = "switch.coffee_maker", state = "on", friendlyName = "Coffee maker"),
        entityState(id = "lock.front_door", state = "locked", friendlyName = "Front door"),
    ),
)

private fun pictureElementsSmallFixture() = CardFixture(
    json = """{"type":"picture-elements","image":"/local/floorplan.png","elements":[{"type":"state-icon","entity":"light.kitchen","style":{"left":"50%","top":"50%"}}]}""",
    snapshot = snapshotOf(kitchenLightState()),
)

private fun pictureElementsLargeFixture() = CardFixture(
    json = """{"type":"picture-elements","image":"/local/floorplan.png","elements":[{"type":"state-icon","entity":"light.kitchen","style":{"left":"25%","top":"40%"}},{"type":"state-icon","entity":"lock.front_door","style":{"left":"60%","top":"30%"}},{"type":"state-icon","entity":"switch.coffee_maker","style":{"left":"75%","top":"70%"}}]}""",
    snapshot = snapshotOf(
        kitchenLightState(),
        entityState(id = "lock.front_door", state = "locked", friendlyName = "Front door"),
        entityState(id = "switch.coffee_maker", state = "on", friendlyName = "Coffee maker"),
    ),
)

private fun entityFilterSmallFixture() = CardFixture(
    json = """{"type":"entity-filter","state_filter":["on"],"entities":["light.kitchen"]}""",
    snapshot = snapshotOf(kitchenLightState()),
)

private fun entityFilterLargeFixture() = CardFixture(
    json = """{"type":"entity-filter","state_filter":["on"],"entities":["light.kitchen","switch.coffee_maker","switch.office_lamp"],"card":{"type":"glance","title":"On now"}}""",
    snapshot = snapshotOf(
        kitchenLightState(),
        entityState(id = "switch.coffee_maker", state = "on", friendlyName = "Coffee maker"),
        entityState(id = "switch.office_lamp", state = "on", friendlyName = "Office lamp"),
    ),
)

private fun verticalStackSmallFixture() = CardFixture(
    json = """{"type":"vertical-stack","cards":[{"type":"tile","entity":"sensor.living_room"}]}""",
    snapshot = snapshotOf(livingRoomTempState()),
)

private fun verticalStackLargeFixture() = CardFixture(
    json = """{"type":"vertical-stack","cards":[{"type":"tile","entity":"sensor.living_room"},{"type":"tile","entity":"light.kitchen"},{"type":"tile","entity":"lock.front_door"}]}""",
    snapshot = snapshotOf(
        livingRoomTempState(),
        kitchenLightState(),
        entityState(id = "lock.front_door", state = "locked", friendlyName = "Front door"),
    ),
)

private fun horizontalStackSmallFixture() = CardFixture(
    json = """{"type":"horizontal-stack","cards":[{"type":"button","entity":"light.kitchen","name":"Kitchen"}]}""",
    snapshot = snapshotOf(kitchenLightState()),
)

private fun horizontalStackLargeFixture() = CardFixture(
    json = """{"type":"horizontal-stack","cards":[{"type":"button","entity":"light.kitchen","name":"Kitchen"},{"type":"button","entity":"sensor.living_room","name":"Temp"},{"type":"button","entity":"lock.front_door","name":"Door"}]}""",
    snapshot = snapshotOf(
        kitchenLightState(),
        livingRoomTempState(),
        entityState(id = "lock.front_door", state = "locked", friendlyName = "Front door"),
    ),
)

private fun gridSmallFixture() = CardFixture(
    json = """{"type":"grid","cards":[{"type":"button","entity":"light.kitchen","name":"Kitchen"}]}""",
    snapshot = snapshotOf(kitchenLightState()),
)

private fun gridLargeFixture() = CardFixture(
    json = """{"type":"grid","cards":[{"type":"button","entity":"light.kitchen","name":"Kitchen"},{"type":"button","entity":"light.office_lamp","name":"Office"},{"type":"button","entity":"switch.coffee_maker","name":"Coffee"},{"type":"button","entity":"lock.front_door","name":"Door"}]}""",
    snapshot = snapshotOf(
        kitchenLightState(),
        entityState(id = "light.office_lamp", state = "off", friendlyName = "Office lamp"),
        entityState(id = "switch.coffee_maker", state = "on", friendlyName = "Coffee maker"),
        entityState(id = "lock.front_door", state = "locked", friendlyName = "Front door"),
    ),
)

private fun bambuPrintStatusFixture() = CardFixture(
    json = """{"type":"custom:ha-bambulab-print_status-card","printer":"<device-id-placeholder>","style":"full"}""",
    snapshot = HaSnapshot(),
)

private fun bambuPrintControlFixture() = CardFixture(
    json = """{"type":"custom:ha-bambulab-print_control-card","printer":"<device-id-placeholder>"}""",
    snapshot = HaSnapshot(),
)

private fun bambuAmsSmallFixture() = CardFixture(
    json = """{"type":"custom:ha-bambulab-ams-card","ams":"<device-id-placeholder>","style":"compact"}""",
    snapshot = HaSnapshot(),
)

private fun bambuAmsLargeFixture() = CardFixture(
    json = """{"type":"custom:ha-bambulab-ams-card","ams":"<device-id-placeholder>","style":"full"}""",
    snapshot = HaSnapshot(),
)

// ── shared entity-state shorthands ──────────────────────────────────

private fun livingRoomTempState() = entityState(
    id = "sensor.living_room",
    state = "21.5",
    friendlyName = "Living Room",
    unit = "°C",
    deviceClass = "temperature",
)

private fun kitchenLightState() = entityState(
    id = "light.kitchen",
    state = "on",
    friendlyName = "Kitchen",
)

private fun outsideTempState() = entityState(
    id = "sensor.outside_temp",
    state = "8.2",
    friendlyName = "Outside",
    unit = "°C",
    deviceClass = "temperature",
)

private fun housePowerState() = entityState(
    id = "sensor.house_power",
    state = "412",
    friendlyName = "House power",
    unit = "W",
    deviceClass = "power",
)
