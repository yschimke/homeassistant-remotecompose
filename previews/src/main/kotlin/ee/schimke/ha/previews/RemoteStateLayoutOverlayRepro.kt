@file:Suppress("RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteStateLayout
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.fillMaxSize as rcFillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.visibility
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.ri
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.ha.rc.CachedCardPreview
import ee.schimke.ha.rc.androidXExperimentalWrap
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.enableRemoteComposeWrapContent
import java.util.UUID

/**
 * Self-contained repro for the alpha010 `RemoteStateLayout` overlay
 * bug.
 *
 * `androidx.compose.remote.core.operations.layout.managers.StateLayout`
 * (bytecode of `remote-core-1.0.0-alpha010.jar`):
 *  - `paint()` offsets 22–49 — on every paint, if
 *    `getInteger(mIndexId) != currentLayoutIndex`, set
 *    `previousLayoutIndex = currentLayoutIndex`,
 *    `currentLayoutIndex = newValue`, `inTransition = true`,
 *    `invalidateMeasure()`.
 *  - `paint()` offsets 149–474 — while `inTransition` and
 *    `previousLayoutIndex != measuredLayoutIndex`: build a paintId
 *    cache from `previous.getChildrenComponents()`, subtract any
 *    paintId that also appears in `current.getChildrenComponents()`,
 *    then iterate previous's children and paint each survivor on top
 *    of the current branch.
 *  - `checkEndOfTransition()` only clears `inTransition` when both
 *    `current.mAnimateMeasure == null` && `previous.mAnimateMeasure
 *    == null`.
 *
 * Net effect: for any `RemoteStateLayout` whose runtime index value
 * differs from the captured `currentLayoutIndex`, the previous
 * branch's distinct (non-paintId-shared) contents bleed through on
 * top of the active branch. For branches that contain a
 * measure-animating child (the gauge sweep) the transition never
 * settles and the overlay persists across frames.
 *
 * Layout: a row of cells at increasing widths gated on `componentWidth()
 * >= 150 dp` (≈ 394 px at 2.625 density).
 *  - FALSE branch (top half): RED block, label `"FALSE"`.
 *  - TRUE branch (bottom half): BLUE block, label `"TRUE"`.
 *
 * Capture-time `componentWidth()` is 0 → the captured
 * `currentLayoutIndex` corresponds to the FALSE branch on every
 * cell.
 *
 * Expected on a fixed alpha:
 *  - narrow cells (`< 150 dp`): predicate stays FALSE → only the
 *    red top-half block renders.
 *  - wide cells (`>= 150 dp`): predicate flips to TRUE → only the
 *    blue bottom-half block renders.
 *
 * Actual on alpha010 (top row of the preview):
 *  - narrow cells: only red (no transition — match).
 *  - **wide cells: BOTH red AND blue render** — the overlay path
 *    paints the previous (FALSE) branch's red block on top of the
 *    active (TRUE) branch's blue block.
 *
 * The bottom row applies a `Modifier.visibility(...)` workaround
 * to each branch's immediate child, gated on the same predicate.
 * `ComponentVisibilityOperation.updateVariables()` writes
 * `mVisibility` on its parent from a `RemoteInt` (1=VISIBLE,
 * 0=GONE, 2=INVISIBLE per `Component$Visibility`), and
 * `Component.paint()` short-circuits on `isGone() ||
 * isInvisible()`, so the previous-branch child paints nothing
 * during the overlay loop. With the workaround applied the wide
 * cells render only the blue block.
 *
 * Pixel-diffing the top-row wide cells against the bottom-row wide
 * cells confirms the bug is in the overlay path, not in the index
 * read or measure pass.
 */
@Preview(
    name = "RemoteStateLayout overlay repro",
    showBackground = false,
    widthDp = 900,
    heightDp = 280,
)
@Composable
fun RemoteStateLayoutOverlayRepro() {
    enableRemoteComposeWrapContent()
    Column(
        modifier = Modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BugCell(100, 80)
            BugCell(130, 80)
            BugCell(150, 80)
            BugCell(180, 80)
            BugCell(220, 80)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FixCell(100, 80)
            FixCell(130, 80)
            FixCell(150, 80)
            FixCell(180, 80)
            FixCell(220, 80)
        }
    }
}

@Composable
private fun BugCell(widthDp: Int, heightDp: Int) {
    LabelledRcCell("bug ${widthDp}×${heightDp}", widthDp, heightDp, BugKey(widthDp, heightDp)) {
        val width = wideExpr()
        val isWide = width.ge(ThresholdPx.rf)
        // Materialise the named expression (#224 forcing function);
        // transparent so it doesn't compete with the branch content.
        RemoteText(text = width.toRemoteString(IntFormat), color = Color(0xFF333333).rc)
        RemoteStateLayout(isWide) { wide ->
            if (wide) {
                BlueBottom(RemoteModifier.rcFillMaxSize())
            } else {
                RedTop(RemoteModifier.rcFillMaxSize())
            }
        }
    }
}

@Composable
private fun FixCell(widthDp: Int, heightDp: Int) {
    LabelledRcCell("fix ${widthDp}×${heightDp}", widthDp, heightDp, FixKey(widthDp, heightDp)) {
        val width = wideExpr()
        val isWide = width.ge(ThresholdPx.rf)
        val wideVisible = isWide.select(1.ri, 0.ri)
        val narrowVisible = isWide.select(0.ri, 1.ri)
        RemoteText(text = width.toRemoteString(IntFormat), color = Color(0xFF333333).rc)
        RemoteStateLayout(isWide) { wide ->
            if (wide) {
                BlueBottom(RemoteModifier.rcFillMaxSize().visibility(wideVisible))
            } else {
                RedTop(RemoteModifier.rcFillMaxSize().visibility(narrowVisible))
            }
        }
    }
}

// Per-call-site unique name — sharing a stable name across captures
// makes alpha010 hand back a cached expression bound to the *first*
// capture's component, so every subsequent cell reads the same baked
// width and the predicate never flips. Mirrors `RemoteSizeBreakpoint`.
@Composable
private fun wideExpr(): RemoteFloat {
    val name = remember { "__overlay_w_${UUID.randomUUID()}" }
    return RemoteFloat.createNamedRemoteFloatExpression(name, RemoteState.Domain.User) {
        componentWidth()
    }
}

@Composable
private fun RedTop(modifier: RemoteModifier) {
    RemoteBox(modifier = modifier, contentAlignment = RemoteAlignment.TopCenter) {
        RemoteBox(
            modifier =
                RemoteModifier.fillMaxWidth().height(28.rdp).background(Color(0xFFE53935).rc),
            contentAlignment = RemoteAlignment.Center,
        ) {
            RemoteText(text = "FALSE".rs, color = Color.White.rc)
        }
    }
}

@Composable
private fun BlueBottom(modifier: RemoteModifier) {
    RemoteBox(modifier = modifier, contentAlignment = RemoteAlignment.BottomCenter) {
        RemoteBox(
            modifier =
                RemoteModifier.fillMaxWidth().height(28.rdp).background(Color(0xFF1E88E5).rc),
            contentAlignment = RemoteAlignment.Center,
        ) {
            RemoteText(text = "TRUE".rs, color = Color.White.rc)
        }
    }
}

@Composable
private fun LabelledRcCell(
    label: String,
    widthDp: Int,
    heightDp: Int,
    key: Any,
    rcContent: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.width(widthDp.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = HaTheme.Dark.secondaryText,
        )
        Box(
            modifier = Modifier.size(widthDp.dp, heightDp.dp).border(1.dp, HaTheme.Dark.divider)
        ) {
            CachedCardPreview(
                cacheKey = key,
                profile = androidXExperimentalWrap,
                modifier = Modifier.fillMaxSize(),
            ) {
                rcContent()
            }
        }
    }
}

private const val ThresholdPx = 394f // 150 dp at 2.625 density

private val IntFormat = java.text.DecimalFormat("0")

private data class BugKey(val widthDp: Int, val heightDp: Int, val version: Int = 1)

private data class FixKey(val widthDp: Int, val heightDp: Int, val version: Int = 1)
