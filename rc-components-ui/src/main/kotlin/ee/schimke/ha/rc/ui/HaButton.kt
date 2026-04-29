@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.ha.rc.components.RemoteHaButton
import ee.schimke.ha.rc.components.RemoteHaToggleButton

/** Compose-UI Tier-2 wrapper around [RemoteHaButton]. */
@Composable
fun HaButton(data: HaButtonUiData, modifier: Modifier = Modifier) {
    HaUiHost(modifier) { RemoteHaButton(data.toRemote()) }
}

/**
 * Compose-UI Tier-2 wrapper around [RemoteHaToggleButton].
 *
 * The Tier-1 toggle button uses an in-document `MutableRemoteBoolean`
 * for optimistic flip; that survives intact here — only the input data
 * is reshaped from plain types.
 */
@Composable
fun HaToggleButton(data: HaButtonUiData, modifier: Modifier = Modifier) {
    HaUiHost(modifier) { RemoteHaToggleButton(data.toRemote()) }
}
