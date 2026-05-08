@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.remote.creation.compose.state.RemoteBoolean
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.RemoteHaToggleSwitch

/**
 * Compose-UI Tier-2 wrapper around [RemoteHaToggleSwitch] — pill-shape
 * switch with a knob position keyed on [initiallyOn]. The Tier-2 entry
 * doesn't expose RemoteCompose state, so the boolean is wrapped as a
 * constant `RemoteBoolean`; tapping fires the host action but does not
 * flip the visual locally. See the Tier-1 doc for the alpha09 layout
 * caveat.
 */
@Composable
fun HaToggleSwitch(
    initiallyOn: Boolean,
    activeAccent: Color,
    inactiveAccent: Color,
    modifier: Modifier = Modifier,
    tapAction: HaAction = HaAction.None,
) {
    HaUiHost(modifier) {
        RemoteHaToggleSwitch(
            isOn = RemoteBoolean(initiallyOn),
            activeAccent = activeAccent.rc,
            inactiveAccent = inactiveAccent.rc,
            tapAction = tapAction,
        )
    }
}
