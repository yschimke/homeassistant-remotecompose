@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.remote.creation.compose.state.rc
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
//
// Tier-2 data already uses plain Kotlin types (String / Color /
// Boolean) — Tier-1 [HaToggleAccent] / [HaTileData] / … now do the
// same since the rc-components refactor in fa24b6d. The wrapper is
// just a field-by-field copy, with [Color] mapped to its `RemoteColor`
// `.rc` form.

internal fun HaToggleAccentUi.toRemote(): HaToggleAccent =
    HaToggleAccent(
        activeAccent = activeAccent.rc,
        inactiveAccent = inactiveAccent.rc,
        initiallyOn = isOn ?: false,
        toggleable = isOn != null,
    )

internal fun HaTileUiData.toRemote(): HaTileData =
    HaTileData(
        entityId = null,
        name = name,
        state = state,
        icon = icon,
        accent = accent.toRemote(),
        tapAction = tapAction,
    )

internal fun HaButtonUiData.toRemote(): HaButtonData =
    HaButtonData(
        entityId = null,
        name = name,
        icon = icon,
        accent = accent.toRemote(),
        showName = showName,
        tapAction = tapAction,
    )

internal fun HaEntityRowUiData.toRemote(): HaEntityRowData =
    HaEntityRowData(
        entityId = null,
        name = name,
        state = state,
        icon = icon,
        accent = accent.toRemote(),
        tapAction = tapAction,
    )

internal fun HaEntitiesUiData.toRemote(): HaEntitiesData =
    HaEntitiesData(
        title = title,
        rows = rows.map { it.toRemote() },
    )

internal fun HaGlanceCellUiData.toRemote(): HaGlanceCellData =
    HaGlanceCellData(
        entityId = null,
        name = name,
        state = state,
        icon = icon,
        accent = accent.toRemote(),
        tapAction = tapAction,
    )

internal fun HaGlanceUiData.toRemote(): HaGlanceData =
    HaGlanceData(
        title = title,
        cells = cells.map { it.toRemote() },
    )

internal fun HaMarkdownUiData.toRemote(): HaMarkdownData =
    HaMarkdownData(title = title, lines = lines)

internal fun HaHeadingUiData.toRemote(): HaHeadingData =
    HaHeadingData(title = title, style = style)

internal fun HaUnsupportedUiData.toRemote(): HaUnsupportedData =
    HaUnsupportedData(cardType = cardType)
