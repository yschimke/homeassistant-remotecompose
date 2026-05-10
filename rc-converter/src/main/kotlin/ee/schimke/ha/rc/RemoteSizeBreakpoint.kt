@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import androidx.compose.remote.creation.compose.layout.RemoteStateLayout
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteInt
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.ri
import androidx.compose.runtime.Composable

/**
 * Switch between layout variants based on the live component width at
 * playback time. Used by Fixed-mode card converters
 * (see [CardSizeMode.Fixed]) to adapt to the runtime widget size — the
 * launcher / Glance Wear tile surface decides the canvas, this picks
 * the variant that fits.
 *
 * Thresholds are an ascending list of widths in document units (≈ dp).
 * For `thresholdsDp = intArrayOf(120, 200)`:
 *
 *   width <  120          → tier 0
 *   120 ≤ width <  200    → tier 1
 *   width ≥  200          → tier 2
 *
 * The lambda is invoked once per tier during capture so each variant is
 * recorded into the document. At playback the active tier is computed
 * from `RemoteFloatContext.componentWidth()` and the runtime swaps to
 * the matching variant — no re-record needed.
 *
 * @param thresholdsDp ascending breakpoint widths; produces
 *   `thresholdsDp.size + 1` tier variants.
 * @param modifier applied to the outer state layout. Pass
 *   `RemoteModifier.fillMaxWidth()` so the playback width reflects the
 *   document's runtime canvas rather than the captured intrinsic.
 * @param content invoked per tier; index ranges `0..thresholdsDp.size`.
 */
@Composable
fun RemoteSizeBreakpoint(
    thresholdsDp: IntArray,
    modifier: RemoteModifier = RemoteModifier,
    content: @Composable (tier: Int) -> Unit,
) {
    require(thresholdsDp.isNotEmpty()) { "RemoteSizeBreakpoint needs at least one threshold" }
    val sortedAscending =
        (0 until thresholdsDp.size - 1).all { thresholdsDp[it] < thresholdsDp[it + 1] }
    require(sortedAscending) { "thresholdsDp must be strictly ascending: ${thresholdsDp.toList()}" }

    // RemoteFloatContext's constructor is internal; the named-expression
    // builder is the public way to surface componentWidth() as a
    // RemoteFloat. Naming by `contentHashCode` lets multiple breakpoints
    // sharing the same thresholds reuse the same registered expression.
    val width =
        RemoteFloat.createNamedRemoteFloatExpression(
            name = "${WidthExpressionPrefix}${thresholdsDp.contentHashCode()}",
            domain = RemoteState.Domain.User,
        ) {
            componentWidth()
        }

    var tier: RemoteInt = 0.ri
    for (t in thresholdsDp) {
        tier = tier + width.ge(t.rf).toRemoteInt()
    }

    val tierKeys = IntArray(thresholdsDp.size + 1) { it }
    RemoteStateLayout(tier, *tierKeys, modifier = modifier) { variant -> content(variant) }
}

private const val WidthExpressionPrefix = "__terrazzo_breakpoint_w_"
