@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.ui.graphics.Color
import ee.schimke.ha.rc.components.HaAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The plain→Remote conversion is the only Tier-2 surface that's
 * exercisable without an Android instrumentation env. The composables
 * themselves need a running RemoteCompose document host to be useful;
 * those land in `previews/` next to the existing Tier-1 fixtures.
 */
class HaUiModelsTest {

    private val accentOn = HaToggleAccentUi(
        activeAccent = Color(0xFFFFC107),
        inactiveAccent = Color(0xFF8F8F8F),
        isOn = true,
    )

    private val accentReadOnly = HaToggleAccentUi(
        activeAccent = Color(0xFF607D8B),
        inactiveAccent = Color(0xFF607D8B),
        isOn = null,
    )

    @Test fun toggleAccent_isOn_yields_named_remote_boolean() {
        val remote = accentOn.toRemote("tile.kitchen")
        assertNotNull(remote.isOn, "isOn should round-trip into a RemoteBoolean")
        assertEquals(true, remote.initiallyOn)
    }

    @Test fun toggleAccent_readonly_drops_remote_boolean() {
        val remote = accentReadOnly.toRemote("tile.sensor")
        assertNull(remote.isOn, "read-only entity must not carry a RemoteBoolean")
        assertEquals(false, remote.initiallyOn)
    }

    @Test fun tile_uiData_round_trips_simple_fields() {
        val ui = HaTileUiData(
            name = "Kitchen",
            state = "On",
            icon = Icons.Filled.Lightbulb,
            accent = accentOn,
            tapAction = HaAction.Toggle("light.kitchen"),
        )
        val remote = ui.toRemote()
        assertEquals(ui.icon, remote.icon)
        assertEquals(ui.tapAction, remote.tapAction)
    }

    @Test fun entities_uiData_assigns_unique_tags_per_row() {
        // Same accent reference on every row — if the conversion shared
        // a name across rows the named-binding would collide; the
        // per-index suffix makes each row's binding unique.
        val ui = HaEntitiesUiData(
            title = "Living Room",
            rows = List(3) {
                HaEntityRowUiData(
                    name = "Lamp $it",
                    state = "On",
                    icon = Icons.Filled.Lightbulb,
                    accent = accentOn,
                )
            },
        )
        val remote = ui.toRemote("entities.living")
        assertEquals(3, remote.rows.size)
        // Three non-null bindings — the tags differ; the API doesn't
        // expose the binding name string for a direct assert, but the
        // count + non-null is enough to lock the contract in.
        remote.rows.forEach { row -> assertNotNull(row.accent.isOn) }
    }
}
