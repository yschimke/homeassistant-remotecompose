@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.remote.creation.compose.state.RemoteBoolean
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaButtonData
import ee.schimke.ha.rc.components.HaEntitiesData
import ee.schimke.ha.rc.components.HaEntityRowData
import ee.schimke.ha.rc.components.HaGlanceCellData
import ee.schimke.ha.rc.components.HaGlanceData
import ee.schimke.ha.rc.components.HaHeadingData
import ee.schimke.ha.rc.components.HaHeadingStyle
import ee.schimke.ha.rc.components.HaMarkdownData
import ee.schimke.ha.rc.components.HaTileData
import ee.schimke.ha.rc.components.HaToggleAccent
import ee.schimke.ha.rc.components.HaUnsupportedData

/**
 * Tier-2 mirror of [HaToggleAccent] expressed in plain Compose types.
 *
 * - [isOn] = `null` ⇒ read-only entity (sensor / weather / …); the
 *   active accent is used unconditionally.
 * - [isOn] = `true` / `false` ⇒ toggleable entity at the given on-state;
 *   colour is selected at document build time. For live re-binding,
 *   reach for the Tier-1 [HaToggleAccent] directly.
 */
data class HaToggleAccentUi(
    val activeAccent: Color,
    val inactiveAccent: Color,
    val isOn: Boolean? = null,
)

data class HaTileUiData(
    val name: String,
    val state: String,
    val icon: ImageVector,
    val accent: HaToggleAccentUi,
    val tapAction: HaAction = HaAction.None,
)

data class HaButtonUiData(
    val name: String,
    val icon: ImageVector,
    val accent: HaToggleAccentUi,
    val showName: Boolean = true,
    val tapAction: HaAction = HaAction.None,
)

data class HaEntityRowUiData(
    val name: String,
    val state: String,
    val icon: ImageVector,
    val accent: HaToggleAccentUi,
    val tapAction: HaAction = HaAction.None,
)

data class HaEntitiesUiData(
    val title: String?,
    val rows: List<HaEntityRowUiData>,
)

data class HaGlanceCellUiData(
    val name: String,
    val state: String,
    val icon: ImageVector,
    val accent: HaToggleAccentUi,
    val tapAction: HaAction = HaAction.None,
)

data class HaGlanceUiData(
    val title: String?,
    val cells: List<HaGlanceCellUiData>,
)

data class HaMarkdownUiData(
    val title: String?,
    val lines: List<String>,
)

data class HaHeadingUiData(
    val title: String,
    val style: HaHeadingStyle = HaHeadingStyle.Title,
)

data class HaUnsupportedUiData(
    val cardType: String,
)

// ——— plain → Remote bridges ———

internal fun HaToggleAccentUi.toRemote(tag: String): HaToggleAccent =
    HaToggleAccent(
        activeAccent = activeAccent.rc,
        inactiveAccent = inactiveAccent.rc,
        isOn = isOn?.let { literalRemoteBoolean("$tag.is_on", it) },
        initiallyOn = isOn ?: false,
    )

/**
 * A constant [RemoteBoolean] expressed as a named binding in the
 * [RemoteState.Domain.User] domain. We piggy-back on the named-binding
 * machinery because the alpha09 SDK exposes no public literal-boolean
 * constructor; the name is otherwise unused (Tier-2 callers don't push
 * live updates — that's Tier-1's job).
 */
private fun literalRemoteBoolean(name: String, value: Boolean): RemoteBoolean =
    RemoteBoolean.createNamedRemoteBoolean(name, value, RemoteState.Domain.User)

internal fun HaTileUiData.toRemote(tag: String = "tile"): HaTileData =
    HaTileData(
        name = name.rs,
        state = state.rs,
        icon = icon,
        accent = accent.toRemote(tag),
        tapAction = tapAction,
    )

internal fun HaButtonUiData.toRemote(tag: String = "button"): HaButtonData =
    HaButtonData(
        name = name.rs,
        icon = icon,
        accent = accent.toRemote(tag),
        showName = showName,
        tapAction = tapAction,
    )

internal fun HaEntityRowUiData.toRemote(tag: String = "row"): HaEntityRowData =
    HaEntityRowData(
        name = name.rs,
        state = state.rs,
        icon = icon,
        accent = accent.toRemote(tag),
        tapAction = tapAction,
    )

internal fun HaEntitiesUiData.toRemote(tag: String = "entities"): HaEntitiesData =
    HaEntitiesData(
        title = title?.rs,
        rows = rows.mapIndexed { i, r -> r.toRemote("$tag.$i") },
    )

internal fun HaGlanceCellUiData.toRemote(tag: String = "cell"): HaGlanceCellData =
    HaGlanceCellData(
        name = name.rs,
        state = state.rs,
        icon = icon,
        accent = accent.toRemote(tag),
        tapAction = tapAction,
    )

internal fun HaGlanceUiData.toRemote(tag: String = "glance"): HaGlanceData =
    HaGlanceData(
        title = title?.rs,
        cells = cells.mapIndexed { i, c -> c.toRemote("$tag.$i") },
    )

internal fun HaMarkdownUiData.toRemote(): HaMarkdownData =
    HaMarkdownData(title = title?.rs, lines = lines.map { it.rs })

internal fun HaHeadingUiData.toRemote(): HaHeadingData =
    HaHeadingData(title = title.rs, style = style)

internal fun HaUnsupportedUiData.toRemote(): HaUnsupportedData =
    HaUnsupportedData(cardType = cardType.rs)
