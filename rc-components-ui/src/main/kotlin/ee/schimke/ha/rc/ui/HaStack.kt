@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ee.schimke.ha.rc.components.RemoteHaGrid
import ee.schimke.ha.rc.components.RemoteHaHorizontalStack
import ee.schimke.ha.rc.components.RemoteHaVerticalStack

/**
 * Tier-2 stack containers mirror the Tier-1 [RemoteHaVerticalStack] /
 * [RemoteHaHorizontalStack] / [RemoteHaGrid] visual contract — same
 * 8.dp spacing — but as ordinary Compose [Column] / [Row] / [FlowRow].
 *
 * Why a re-implementation here and not a `HaUiHost` wrap, when every
 * other Tier-2 component delegates to its Tier-1 sibling: the Tier-1
 * stacks expect `@RemoteComposable` children. Tier-2 callers don't have
 * those — they have plain `@Composable` children, each of which is
 * already its own embedded RemoteCompose document. Hosting a Tier-2
 * `HaTile` inside a Tier-1 `RemoteHaVerticalStack` would mean nesting a
 * `RemotePreview` inside a `@RemoteComposable` capture scope, which the
 * SDK doesn't support.
 *
 * This matches the project-level dashboard architecture: each card is
 * its own document, the dashboard layout is plain Compose. Tier-2
 * stacks here are layout convenience over the same model.
 */
@Composable
fun HaVerticalStack(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        content()
    }
}

@Composable
fun HaHorizontalStack(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        content()
    }
}

@Composable
fun HaGrid(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}
