@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteStateLayout
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import java.text.DecimalFormat

/**
 * Switch between layout variants based on the live component width at
 * playback time. Used by Fixed-mode card converters
 * (see [CardSizeMode.Fixed]) to adapt to the runtime widget size — the
 * launcher / Glance Wear tile surface decides the canvas, this picks
 * the variant that fits.
 *
 * Thresholds are an ascending list of widths in dp. For
 * `thresholdsDp = intArrayOf(120, 200)`:
 *
 *   width <  120 dp           → tier 0
 *   120 dp ≤ width < 200 dp   → tier 1
 *   width ≥  200 dp           → tier 2
 *
 * The lambda is invoked once per tier during capture so each variant
 * is recorded into the document. At playback the active tier is
 * computed from `RemoteFloatContext.componentWidth()` and the runtime
 * swaps to the matching variant — no re-record needed.
 *
 * Implementation notes:
 *
 *   * `componentWidth()` reports the runtime canvas width in pixels.
 *     We compare against `<dp>.rdp.toPx()` so the dp→px conversion
 *     happens at playback against the host density — the captured
 *     document plays correctly across phone, wear, and launcher
 *     densities without re-encoding.
 *
 *   * The `RemoteStateLayout(RemoteInt, IntArray)` overload is broken
 *     in `androidx.compose.remote:remote-creation-compose:1.0.0-alpha010`
 *     — it always selects `keys[0]` regardless of the live int value
 *     (#224). The `RemoteStateLayout(RemoteBoolean)` overload works,
 *     so we lower the ladder into a chain of nested booleans, one
 *     per threshold.
 *
 *   * The named expression that backs `componentWidth()` only writes
 *     itself into the document when it's visibly referenced by a
 *     drawing node — reading it transitively via the state-layout's
 *     bool predicate isn't enough (#224). We work around it by
 *     emitting a transparent `RemoteText` alongside the inner
 *     state-layout.
 *
 * @param thresholdsDp ascending breakpoint widths in dp; produces
 *   `thresholdsDp.size + 1` tier variants.
 * @param modifier applied to the outermost state layout. Pass
 *   `RemoteModifier.fillMaxWidth()` (or `fillMaxSize()`) so the
 *   playback width reflects the host's runtime canvas rather than
 *   the captured intrinsic.
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

    // Use a per-call-site unique name so each breakpoint registers its
    // own expression. Sharing a stable name across captures (the
    // tempting "memoize by thresholds" path) lets alpha010 hand back a
    // cached expression bound to the *first* capture's component, which
    // makes every subsequent cell read the same baked width.
    val uniqueName = remember { "${WidthExpressionPrefix}${java.util.UUID.randomUUID()}" }
    val width =
        RemoteFloat.createNamedRemoteFloatExpression(
            name = uniqueName,
            domain = RemoteState.Domain.User,
        ) {
            componentWidth()
        }

    RemoteBox(modifier = modifier) {
        // TODO(#224): drop this transparent forcing-function once the
        // alpha010 named-expression bug is fixed upstream.
        //
        // The named expression that backs `componentWidth()` is only
        // written into the captured document if it's referenced by a
        // visible node. Reading it transitively via
        // `width.ge(...)` → RemoteBoolean → state-layout predicate
        // does NOT trigger materialization in alpha010 — the runtime
        // resolves the unregistered name to 0, the predicate is false
        // for every cell, and every breakpoint collapses to tier 0.
        //
        // Emitting a RemoteText with `Color.Transparent` alongside the
        // inner state-layout is the simplest forcing function: the
        // width string isn't visible, but it pulls the named float
        // into the document so the runtime can evaluate the predicate
        // correctly.
        RemoteText(
            text = width.toRemoteString(InvisibleFormat),
            color = Color.Transparent.rc,
        )
        BreakpointTier(
            width = width,
            thresholdsDp = thresholdsDp,
            baseTier = 0,
            modifier = RemoteModifier,
            content = content,
        )
    }
}

private val InvisibleFormat = DecimalFormat("0")

/**
 * Recursive helper that walks the threshold list highest-first,
 * picking the upper variant when `width >= thresholdsDp[hi]` and
 * recursing on the remaining smaller thresholds otherwise. Lowers to
 * `RemoteStateLayout(RemoteBoolean)`, which is the only state-layout
 * overload that respects the live state in alpha010.
 */
@Composable
private fun BreakpointTier(
    width: RemoteFloat,
    thresholdsDp: IntArray,
    baseTier: Int,
    modifier: RemoteModifier,
    content: @Composable (tier: Int) -> Unit,
) {
    if (thresholdsDp.isEmpty()) {
        content(baseTier)
        return
    }
    val highestIndex = thresholdsDp.size - 1
    val highestThresholdPx = thresholdsDp[highestIndex].rdp.toPx()
    val isAtOrAbove = width.ge(highestThresholdPx)
    RemoteStateLayout(isAtOrAbove, modifier = modifier) { atOrAbove ->
        if (atOrAbove) {
            content(baseTier + thresholdsDp.size)
        } else {
            BreakpointTier(
                width = width,
                thresholdsDp = thresholdsDp.copyOfRange(0, highestIndex),
                baseTier = baseTier,
                modifier = RemoteModifier,
                content = content,
            )
        }
    }
}

private const val WidthExpressionPrefix = "__terrazzo_breakpoint_w_"
