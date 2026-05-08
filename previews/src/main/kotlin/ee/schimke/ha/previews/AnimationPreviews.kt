@file:Suppress("RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.state.RemoteBoolean
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.tooling.preview.RemotePreview
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.LocalPreviewClock
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.androidXExperimental
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.RemoteHaToggleSwitch
import ee.schimke.ha.rc.components.RemoteHaToggleSwitchByProgress
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Animation flipbooks for the in-document tweens introduced by
 * `Animations.kt`.
 *
 * Each strip is a `@PreviewParameter` fan-out — one PNG per frame at
 * evenly spaced progress / value points. Reading the rendered PNGs in
 * order is the recording: the toggle knob slides, the track colour
 * cross-fades, the gauge sweep grows, the severity band changes from
 * red → yellow → green as the value crosses the configured thresholds.
 *
 * For each component the file ships:
 * - **`*_Strip_*`** — frozen frames driven via a by-progress entry
 *   point (toggle) or distinct entity values (gauge). Deterministic;
 *   the host builds one `.rc` document per frame.
 * - **`*_Animated_*`** — a single live document, encoded once. Tapping
 *   it in an interactive player flips the in-doc state and the player
 *   tweens between resting frames. The static PNG only shows the
 *   resting state; this exists so the compose-preview tooling has a
 *   real animated document to record from.
 */

private val PreviewActiveAccent = Color(0xFF2196F3)
private val PreviewInactiveAccent = Color(0xFFB0B0B0)
private val PreviewNow: ZonedDateTime =
    ZonedDateTime.of(2026, 5, 5, 10, 8, 0, 0, ZoneOffset.UTC)

// ——— toggle switch ———

/**
 * Knob-progress fan-out for [RemoteHaToggleSwitchByProgress] —
 * 0%, 25%, 50%, 75%, 100% of the off→on travel. The intermediate
 * frames are exactly what the in-doc animation replays at runtime;
 * the strip is a stop-motion of the same tween.
 */
class ToggleProgressFramesProvider : PreviewParameterProvider<Pair<String, Float>> {
    override val values: Sequence<Pair<String, Float>> = sequenceOf(
        "0pct" to 0f,
        "25pct" to 0.25f,
        "50pct" to 0.5f,
        "75pct" to 0.75f,
        "100pct" to 1f,
    )
}

@Preview(name = "toggle-anim (light)", showBackground = false, widthDp = 40, heightDp = 24)
@Composable
fun Toggle_Animation_Light(
    @PreviewParameter(ToggleProgressFramesProvider::class) frame: Pair<String, Float>,
) {
    RemotePreview(profile = androidXExperimental) {
        ProvideHaTheme(HaTheme.Light) {
            RemoteHaToggleSwitchByProgress(
                progress = frame.second.rf,
                activeAccent = PreviewActiveAccent.rc,
                inactiveAccent = PreviewInactiveAccent.rc,
            )
        }
    }
}

@Preview(name = "toggle-anim (dark)", showBackground = false, widthDp = 40, heightDp = 24)
@Composable
fun Toggle_Animation_Dark(
    @PreviewParameter(ToggleProgressFramesProvider::class) frame: Pair<String, Float>,
) {
    RemotePreview(profile = androidXExperimental) {
        ProvideHaTheme(HaTheme.Dark) {
            RemoteHaToggleSwitchByProgress(
                progress = frame.second.rf,
                activeAccent = PreviewActiveAccent.rc,
                inactiveAccent = PreviewInactiveAccent.rc,
            )
        }
    }
}

/**
 * Public-entry-point smoke preview. The document encodes
 * `animateRemoteFloat` over the `isOn`-derived progress, so a host
 * writing back to a named `RemoteBoolean` would tween the knob and
 * colour live. Here `isOn` is a constant `RemoteBoolean(false)`, so
 * the static PNG just shows the resting off-state.
 */
@Preview(name = "toggle-animated (light)", showBackground = false, widthDp = 40, heightDp = 24)
@Composable
fun Toggle_Animated_Light() {
    RemotePreview(profile = androidXExperimental) {
        ProvideHaTheme(HaTheme.Light) {
            RemoteHaToggleSwitch(
                isOn = RemoteBoolean(false),
                activeAccent = PreviewActiveAccent.rc,
                inactiveAccent = PreviewInactiveAccent.rc,
            )
        }
    }
}

@Preview(name = "toggle-animated (dark)", showBackground = false, widthDp = 40, heightDp = 24)
@Composable
fun Toggle_Animated_Dark() {
    RemotePreview(profile = androidXExperimental) {
        ProvideHaTheme(HaTheme.Dark) {
            RemoteHaToggleSwitch(
                isOn = RemoteBoolean(false),
                activeAccent = PreviewActiveAccent.rc,
                inactiveAccent = PreviewInactiveAccent.rc,
            )
        }
    }
}

// ——— gauge ———

/**
 * Gauge value fan-out — five frames at 0%, 25%, 50%, 75%, 100%.
 *
 * Each frame is a different `.rc` document seeded with a different
 * battery-percent value. The arc sweep grows; the severity band
 * (red ≤ 30, yellow ≤ 60, green > 60) transitions exactly as a host
 * push would, since the `.numeric_state` binding is what
 * `RemoteHaGauge` already animates between snapshots.
 */
class GaugeValueFramesProvider : PreviewParameterProvider<Pair<String, Int>> {
    override val values: Sequence<Pair<String, Int>> = sequenceOf(
        "0pct" to 0,
        "25pct" to 25,
        "50pct" to 50,
        "75pct" to 75,
        "100pct" to 100,
    )
}

private fun gaugeFrameSnapshot(value: Int): HaSnapshot = snapshot(
    state(
        "sensor.gauge_demo",
        value.toString(),
        mapOf(
            "friendly_name" to "Battery",
            "unit_of_measurement" to "%",
            "device_class" to "battery",
        ),
    ),
)

private fun gaugeFrameCardJson(): String =
    """{"type":"gauge","entity":"sensor.gauge_demo","name":"Battery %",
       "min":0,"max":100,"severity":{"green":60,"yellow":30,"red":0}}"""

@Composable
private fun GaugeFrameHost(theme: HaTheme, content: @Composable () -> Unit) {
    RemotePreview(profile = androidXExperimental) {
        CompositionLocalProvider(LocalPreviewClock provides PreviewNow) {
            ProvideCardRegistry(defaultRegistry()) {
                ProvideHaTheme(theme) { content() }
            }
        }
    }
}

@Preview(name = "gauge-anim (light)", showBackground = false, widthDp = 187, heightDp = 150)
@Composable
fun Gauge_Animation_Light(
    @PreviewParameter(GaugeValueFramesProvider::class) frame: Pair<String, Int>,
) = GaugeFrameHost(HaTheme.Light) {
    RenderChild(
        card(gaugeFrameCardJson()),
        gaugeFrameSnapshot(frame.second),
        RemoteModifier.fillMaxWidth(),
    )
}

@Preview(name = "gauge-anim (dark)", showBackground = false, widthDp = 187, heightDp = 150)
@Composable
fun Gauge_Animation_Dark(
    @PreviewParameter(GaugeValueFramesProvider::class) frame: Pair<String, Int>,
) = GaugeFrameHost(HaTheme.Dark) {
    RenderChild(
        card(gaugeFrameCardJson()),
        gaugeFrameSnapshot(frame.second),
        RemoteModifier.fillMaxWidth(),
    )
}
