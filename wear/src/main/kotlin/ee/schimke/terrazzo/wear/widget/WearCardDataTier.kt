package ee.schimke.terrazzo.wear.widget

/**
 * Per-card-type classification of how much of a card's data hierarchy
 * can survive the wear sync proto and the slot widget canvas.
 *
 * See `docs/architecture/adaptive-card-layouts.md` § "Wear data-layer
 * reality" for the motivation. In short: `LiveValues` carries
 * state + friendly_name + unit + device_class per entity — P1 + P2 +
 * (unit-only) P3 in priority terms. Cards whose identity depends on
 * P4 / P5 data (forecast arrays, history series, list payloads, log
 * entries, calendar events) cannot fill the large slot container; at
 * 200×112 dp they would render as their stripped tier with a lot of
 * empty space. For those, advertising the large slot service is just
 * noise in the system widget picker.
 *
 * Drives two things:
 *   - [WearSlotsController][ee.schimke.terrazzo.wear.sync.WearSlotsController.applySlots]
 *     gates `enableLarge` on this classifier so the picker hides the
 *     large variant for low-data card types regardless of the
 *     phone-side `SlotSizePref`.
 *   - The `@Preview` convention in
 *     [CardSlotWidgetPreviews][CardSlotWidgetPreviews_kt]: small-only
 *     for [SmallOnly] types; small + large for [Both] types.
 *
 * Unknown / future card types default to [Both]: safer to advertise
 * both and let the renderer's ladder pick the right tier than to
 * silently hide the large variant for a card that could fill it.
 */
internal enum class WearCardDataTier {
    /**
     * Card needs only what fits at the small (200×60 dp) slot
     * container. Includes both cards with hardly any live data
     * (`heading`, `markdown`, `clock`) and cards whose identity
     * depends on payloads the wear sync proto doesn't carry
     * (`weather-forecast`, `logbook`, `history-graph`,
     * `statistics-graph`, `todo-list`, `calendar`).
     */
    SmallOnly,

    /**
     * Card renders meaningfully at both small (200×60 dp) and large
     * (200×112 dp) slot containers, either because it's a single-entity
     * card that scales its own tier (tile, gauge, thermostat) or
     * because it's a multi-entity card whose extra rows / cells fill
     * the large canvas (entities, glance, area, *-stack).
     */
    Both;

    val supportsLarge: Boolean get() = this == Both
}

/**
 * Classify a card-config `type` string. Type strings come straight
 * from the lovelace card config (`"tile"`, `"weather-forecast"`,
 * `"custom:ha-bambulab-print_status-card"`, …); this function returns
 * [WearCardDataTier.Both] for any type not explicitly listed so a new
 * card never silently loses its large slot.
 */
internal fun wearCardDataTier(cardType: String): WearCardDataTier =
    when (cardType) {
        // Static / config-only content — nothing for the large canvas
        // to fill with.
        "heading",
        "markdown",
        "clock",
        // Identity depends on P4 / P5 payloads the wear sync proto
        // doesn't carry (forecast array, history points, statistics
        // series, log entries, todo items, calendar events). At the
        // large container these render as their stripped tier with a
        // lot of empty space.
        "weather-forecast",
        "logbook",
        "history-graph",
        "statistics-graph",
        "todo-list",
        "calendar" -> WearCardDataTier.SmallOnly

        else -> WearCardDataTier.Both
    }
