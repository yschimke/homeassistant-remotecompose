@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.ui.graphics.Color
import ee.schimke.ha.rc.components.HaAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test fun toggleAccent_isOn_marks_toggleable_and_seeds_initial_state() {
        val remote = accentOn.toRemote()
        assertTrue(remote.toggleable, "isOn != null implies toggleable")
        assertEquals(true, remote.initiallyOn)
    }

    @Test fun toggleAccent_readonly_drops_toggleable_flag() {
        val remote = accentReadOnly.toRemote()
        assertFalse(remote.toggleable, "isOn = null must produce a non-toggleable accent")
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
        assertEquals(ui.name, remote.name)
        assertEquals(ui.state, remote.state.constantValueOrNull)
        assertEquals(ui.icon, remote.icon)
        assertEquals(ui.tapAction, remote.tapAction)
    }

    @Test fun entities_uiData_carries_each_row_through() {
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
        val remote = ui.toRemote()
        assertEquals(3, remote.rows.size)
        remote.rows.forEachIndexed { i, row ->
            assertEquals("Lamp $i", row.name)
            assertTrue(row.accent.toggleable)
        }
    }
}
