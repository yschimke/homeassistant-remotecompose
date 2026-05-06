package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.state.RemoteBoolean
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Per-component data models. Each `RemoteHa*` composable takes a single
 * data parameter instead of an expanding arg list — a replacement
 * composable (custom themed tile, alternative entity row, …) can
 * consume the same model.
 *
 * Only fields that affect rendering / semantics live here; layout
 * concerns (Modifier) stay on the composable signature.
 */

/**
 * State-binding + color pair for a toggleable entity. The component
 * uses `isOn.select(activeAccent, inactiveAccent)` so a live player
 * can flip the accent without re-encoding the document. For
 * non-toggleable entities (sensors, etc.) set [isOn] = null and the
 * activeAccent is used unconditionally.
 *
 * [initiallyOn] is the boolean value at authoring time — named
 * `RemoteBoolean`s don't carry a `constantValueOrNull`, so the
 * composable needs this separately to seed an in-document
 * `MutableRemoteBoolean` for the optimistic-click pattern.
 */
data class HaToggleAccent(
    val activeAccent: RemoteColor,
    val inactiveAccent: RemoteColor,
    val isOn: RemoteBoolean? = null,
    val initiallyOn: Boolean = false,
)

/** HA tile card model. */
data class HaTileData(
    val name: RemoteString,
    val state: RemoteString,
    val icon: ImageVector,
    val accent: HaToggleAccent,
    val tapAction: HaAction = HaAction.None,
)

/** HA button card model. */
data class HaButtonData(
    val name: RemoteString,
    val icon: ImageVector,
    val accent: HaToggleAccent,
    val showName: Boolean = true,
    val tapAction: HaAction = HaAction.None,
)

/** One row in an Entities card / the entire Entity card. */
data class HaEntityRowData(
    val name: RemoteString,
    val state: RemoteString,
    val icon: ImageVector,
    val accent: HaToggleAccent,
    val tapAction: HaAction = HaAction.None,
)

/** Entities card (title + rows). The converter collects rows into this list. */
data class HaEntitiesData(
    val title: RemoteString?,
    val rows: List<HaEntityRowData>,
)

/** One cell in a Glance card. */
data class HaGlanceCellData(
    val name: RemoteString,
    val state: RemoteString,
    val icon: ImageVector,
    val accent: HaToggleAccent,
    val tapAction: HaAction = HaAction.None,
)

/** Glance card (title + cells). */
data class HaGlanceData(
    val title: RemoteString?,
    val cells: List<HaGlanceCellData>,
)

/** Markdown card. */
data class HaMarkdownData(
    val title: RemoteString?,
    val lines: List<RemoteString>,
)

/** Heading card. */
data class HaHeadingData(
    val title: RemoteString,
    val style: HaHeadingStyle = HaHeadingStyle.Title,
)

/** Unsupported-card placeholder. */
data class HaUnsupportedData(
    val cardType: RemoteString,
)

/**
 * `picture-entity` card model. We can't pull the live camera frame /
 * image entity into a `.rc` document, so the renderer emits a tinted
 * placeholder area with a domain-appropriate icon and the entity name
 * + state at the bottom — matching the visual slot the dashboard
 * expects.
 */
data class HaPictureEntityData(
    val name: RemoteString,
    val state: RemoteString,
    val icon: ImageVector,
    val accent: HaToggleAccent,
    val showName: Boolean = true,
    val showState: Boolean = true,
    val tapAction: HaAction = HaAction.None,
)

/** `gauge` card model. Half-circle dial with current value + name + range. */
data class HaGaugeData(
    val name: RemoteString,
    val valueText: RemoteString,
    val unit: String?,
    /** Current value in the gauge's [min..max] range; clamped at render time. */
    val value: Double,
    val min: Double,
    val max: Double,
    /** Severity bands sampled at the value position: green / yellow / red. */
    val severity: HaGaugeSeverity = HaGaugeSeverity.None,
    val tapAction: HaAction = HaAction.None,
)

enum class HaGaugeSeverity { None, Normal, Warning, Critical }

/** `weather-forecast` card model. */
data class HaWeatherForecastData(
    val name: RemoteString,
    val condition: RemoteString,
    /** Current temperature display, including unit. */
    val temperature: RemoteString,
    /** Optional secondary line — feels like, low/high, or extra detail. */
    val supportingLine: RemoteString?,
    val icon: ImageVector,
    val days: List<HaWeatherDay> = emptyList(),
)

/** One column in a weather forecast strip. */
data class HaWeatherDay(
    val label: RemoteString,
    val high: RemoteString,
    val low: RemoteString,
    val icon: ImageVector,
)

/**
 * `logbook` card model. Each entry is one row: name, what changed, and
 * a short "when" suffix (relative time).
 */
data class HaLogbookData(
    val title: RemoteString?,
    val entries: List<HaLogbookEntry>,
)

data class HaLogbookEntry(
    val name: RemoteString,
    val message: RemoteString,
    val whenText: RemoteString,
    val icon: ImageVector,
)

/**
 * `history-graph` card model. Each row carries the numeric samples + a
 * pre-formatted summary string ("21.4 °C", "min – max (N)") so the
 * renderer doesn't need to know about units.
 */
data class HaHistoryGraphData(
    val title: RemoteString?,
    /** "Last 24h" / "Last 12h" — whatever `hours_to_show` resolved to. */
    val rangeLabel: RemoteString,
    val rows: List<HaHistoryGraphRow>,
)

data class HaHistoryGraphRow(
    val name: RemoteString,
    val summary: RemoteString,
    val accent: Color,
    /** Numeric samples in order. Empty → renders a "no data" stub. */
    val points: List<Float>,
)
