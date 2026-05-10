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
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteStateLayout
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize as rcFillMaxSize
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteInt
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.ri
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.ha.rc.CachedCardPreview
import ee.schimke.ha.rc.androidXExperimentalWrap
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.enableRemoteComposeWrapContent
import java.text.DecimalFormat

/**
 * Runtime probe for `RemoteFloatContext.componentWidth()`.
 *
 * Each cell captures a single `RemoteText` whose value is the live
 * component width formatted as an integer string. If the named
 * expression evaluates at playback the rendered text matches the
 * cell's pixel width; if it bakes a constant at recording time every
 * cell shows the same number.
 */

private val IntFormat = DecimalFormat("0")

@Preview(name = "componentWidth runtime probe", showBackground = false, widthDp = 900, heightDp = 140)
@Composable
fun ComponentWidthRuntimeProbe() {
    enableRemoteComposeWrapContent()
    Row(
        modifier = Modifier.padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProbeCell(80, 80)
        ProbeCell(120, 80)
        ProbeCell(160, 80)
        ProbeCell(200, 80)
        ProbeCell(240, 80)
    }
}

@Composable
private fun ProbeCell(widthDp: Int, heightDp: Int) {
    Column(
        modifier = Modifier.width(widthDp.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "${widthDp}×${heightDp}",
            style = MaterialTheme.typography.labelSmall,
            color = HaTheme.Dark.secondaryText,
        )
        Box(
            modifier = Modifier
                .size(widthDp.dp, heightDp.dp)
                .border(1.dp, HaTheme.Dark.divider),
        ) {
            CachedCardPreview(
                cacheKey = ProbeKey(widthDp, heightDp),
                profile = androidXExperimentalWrap,
                modifier = Modifier.fillMaxSize(),
            ) {
                val width =
                    RemoteFloat.createNamedRemoteFloatExpression(
                        name = "__cw_probe",
                        domain = RemoteState.Domain.User,
                    ) {
                        componentWidth()
                    }
                // Hard-coded threshold of 350 (px) so the boolean
                // flips at runtime: cells ≥ 160 dp at 2.625 density
                // (≥ 420 px) come in as TRUE; smaller cells as FALSE.
                val isLarge = width.ge(350f.rf)
                RemoteColumn(modifier = RemoteModifier.rcFillMaxSize()) {
                    RemoteText(
                        text = width.toRemoteString(IntFormat),
                        color = Color.White.rc,
                    )
                    RemoteText(
                        text = isLarge.select("BIG".rs, "small".rs),
                        color = Color.White.rc,
                    )
                    // Boolean state layout — does it pick the right
                    // variant at playback?
                    RemoteStateLayout(isLarge) { on ->
                        RemoteText(
                            text = if (on) "B-on".rs else "B-off".rs,
                            color = Color.White.rc,
                        )
                    }
                }
            }
        }
    }
}

private data class ProbeKey(val widthDp: Int, val heightDp: Int)
