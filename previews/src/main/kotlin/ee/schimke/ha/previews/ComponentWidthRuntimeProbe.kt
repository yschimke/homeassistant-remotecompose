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
import androidx.compose.remote.creation.compose.layout.RemoteStateLayout
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize as rcFillMaxSize
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.ha.rc.CachedCardPreview
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.androidXExperimentalWrap
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.enableRemoteComposeWrapContent

/**
 * Runtime probe comparing the inline `RemoteStateLayout(RemoteBoolean)`
 * path (verified in earlier probe iterations) against the
 * `RemoteSizeBreakpoint` helper at the same five canvas widths. Top
 * row runs the inline path with a hand-rolled boolean state layout;
 * bottom row drives the helper with a 120 dp threshold (≈ 315 px at
 * 2.625 density).
 */

@Preview(name = "componentWidth runtime probe", showBackground = false, widthDp = 900, heightDp = 240)
@Composable
fun ComponentWidthRuntimeProbe() {
    enableRemoteComposeWrapContent()
    Column(
        modifier = Modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InlineCell(80, 80)
            InlineCell(120, 80)
            InlineCell(160, 80)
            InlineCell(200, 80)
            InlineCell(240, 80)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HelperCell(80, 80)
            HelperCell(120, 80)
            HelperCell(160, 80)
            HelperCell(200, 80)
            HelperCell(240, 80)
        }
    }
}

@Composable
private fun InlineCell(widthDp: Int, heightDp: Int) {
    LabelledRcCell(label = "in ${widthDp}×${heightDp}", widthDp, heightDp, ProbeKey("in", widthDp, heightDp)) {
        val width =
            RemoteFloat.createNamedRemoteFloatExpression(
                name = "__cw_probe_inline",
                domain = RemoteState.Domain.User,
            ) {
                componentWidth()
            }
        val isLarge = width.ge(315f.rf) // 120 dp at 2.625 density
        // No modifier on the state-layout (matches v4 setup). Width
        // string drawn alongside as a sibling so the named expression
        // is materialized in the document.
        RemoteText(text = width.toRemoteString(IntFormat), color = Color.White.rc)
        RemoteStateLayout(isLarge) { on ->
            RemoteText(
                text = if (on) "INLINE-ON".rs else "INLINE-OFF".rs,
                color = Color.White.rc,
            )
        }
    }
}

private val IntFormat = java.text.DecimalFormat("0")

@Composable
private fun HelperCell(widthDp: Int, heightDp: Int) {
    LabelledRcCell(label = "helper ${widthDp}×${heightDp}", widthDp, heightDp, ProbeKey("hp", widthDp, heightDp)) {
        RemoteSizeBreakpoint(
            thresholdsDp = intArrayOf(120),
            modifier = RemoteModifier.rcFillMaxSize(),
        ) { tier ->
            RemoteText(
                text = if (tier == 0) "HELPER-T0".rs else "HELPER-T1".rs,
                color = Color.White.rc,
            )
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
            modifier = Modifier.size(widthDp.dp, heightDp.dp).border(1.dp, HaTheme.Dark.divider),
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

private data class ProbeKey(
    val tag: String,
    val widthDp: Int,
    val heightDp: Int,
    val version: Int = 10,
)
