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
 */
data class HaToggleAccent(
    val activeAccent: RemoteColor,
    val inactiveAccent: RemoteColor,
    val isOn: RemoteBoolean? = null,
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
