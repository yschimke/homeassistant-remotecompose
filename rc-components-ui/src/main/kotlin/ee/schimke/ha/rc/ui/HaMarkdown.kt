@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.ha.rc.components.RemoteHaMarkdown

/** Compose-UI Tier-2 wrapper around [RemoteHaMarkdown]. */
@Composable
fun HaMarkdown(data: HaMarkdownUiData, modifier: Modifier = Modifier) {
    HaUiHost(modifier) { RemoteHaMarkdown(data.toRemote()) }
}
