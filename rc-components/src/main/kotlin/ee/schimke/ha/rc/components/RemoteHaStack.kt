@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteFlowRow
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.runtime.Composable

/**
 * Layout wrappers mirroring HA's `vertical-stack`, `horizontal-stack` and
 * `grid` card types. The converter in `:rc-converter` is responsible for
 * emitting each child card.
 */

@Composable
@RemoteComposable
fun RemoteHaVerticalStack(
    modifier: RemoteModifier = RemoteModifier,
    content: @Composable @RemoteComposable () -> Unit,
) {
    RemoteColumn(
        modifier = modifier,
        verticalArrangement = RemoteArrangement.spacedBy(8.rdp),
    ) { content() }
}

@Composable
@RemoteComposable
fun RemoteHaHorizontalStack(
    modifier: RemoteModifier = RemoteModifier,
    content: @Composable @RemoteComposable () -> Unit,
) {
    RemoteRow(
        modifier = modifier,
        horizontalArrangement = RemoteArrangement.spacedBy(8.rdp),
    ) { content() }
}

/**
 * HA `grid` card — N-column flow of children. Approximates `hui-grid-card.ts`;
 * actual breakpoint logic lives per-row in HA (uses CSS grid), we use
 * [RemoteFlowRow] which wraps when the row overflows.
 */
@Composable
@RemoteComposable
fun RemoteHaGrid(
    modifier: RemoteModifier = RemoteModifier,
    content: @Composable @RemoteComposable () -> Unit,
) {
    RemoteFlowRow(
        modifier = modifier,
        horizontalArrangement = RemoteArrangement.spacedBy(8.rdp),
        verticalArrangement = RemoteArrangement.spacedBy(8.rdp),
    ) { content() }
}
