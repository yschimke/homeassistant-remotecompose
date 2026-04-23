@file:Suppress("RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.tooling.preview.RemotePreview
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.ha.rc.androidXExperimental
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.RemoteHaToggleSwitch
import ee.schimke.ha.rc.components.RemoteHaToggleSwitchByProgress

/**
 * Dedicated previews for [RemoteHaToggleSwitch].
 *
 * - `ToggleByProgress_*` calls the progress-driven inner composable
 *   directly with literal RemoteFloats (0 / 0.5 / 1.0) — these prove the
 *   visual works for any constant progress.
 * - `ToggleInitial_*` uses the public [RemoteHaToggleSwitch] entry
 *   point. Currently STATIC: the host-side `Boolean` picks one of the
 *   two literal RemoteFloats; the document never animates between them.
 *   See `docs/bugs/rc-alpha08-select-derived-float-layout.md` for why.
 */

private val PreviewActiveAccent = Color(0xFF2196F3)
private val PreviewInactiveAccent = Color(0xFFB0B0B0)

@Composable
private fun SwitchHost(theme: HaTheme, content: @Composable () -> Unit) {
    RemotePreview(profile = androidXExperimental) {
        ProvideHaTheme(theme) { content() }
    }
}

// ——— progress-driven (deterministic) ———

@Preview(name = "toggle progress=0.0", showBackground = false, widthDp = 40, heightDp = 24)
@Composable
fun ToggleByProgress_Off() = SwitchHost(HaTheme.Light) {
    RemoteHaToggleSwitchByProgress(
        progress = 0f.rf,
        activeAccent = PreviewActiveAccent.rc,
        inactiveAccent = PreviewInactiveAccent.rc,
    )
}

@Preview(name = "toggle progress=0.5", showBackground = false, widthDp = 40, heightDp = 24)
@Composable
fun ToggleByProgress_Mid() = SwitchHost(HaTheme.Light) {
    RemoteHaToggleSwitchByProgress(
        progress = 0.5f.rf,
        activeAccent = PreviewActiveAccent.rc,
        inactiveAccent = PreviewInactiveAccent.rc,
    )
}

@Preview(name = "toggle progress=1.0", showBackground = false, widthDp = 40, heightDp = 24)
@Composable
fun ToggleByProgress_On() = SwitchHost(HaTheme.Light) {
    RemoteHaToggleSwitchByProgress(
        progress = 1f.rf,
        activeAccent = PreviewActiveAccent.rc,
        inactiveAccent = PreviewInactiveAccent.rc,
    )
}

// ——— boolean-driven (the public API an entity row uses) ———

@Preview(name = "toggle initial=off", showBackground = false, widthDp = 40, heightDp = 24)
@Composable
fun ToggleInitial_Off() = SwitchHost(HaTheme.Light) {
    RemoteHaToggleSwitch(
        initiallyOn = false,
        activeAccent = PreviewActiveAccent.rc,
        inactiveAccent = PreviewInactiveAccent.rc,
    )
}

@Preview(name = "toggle initial=on", showBackground = false, widthDp = 40, heightDp = 24)
@Composable
fun ToggleInitial_On() = SwitchHost(HaTheme.Light) {
    RemoteHaToggleSwitch(
        initiallyOn = true,
        activeAccent = PreviewActiveAccent.rc,
        inactiveAccent = PreviewInactiveAccent.rc,
    )
}

@Preview(name = "toggle initial=on (dark)", showBackground = false, widthDp = 40, heightDp = 24)
@Composable
fun ToggleInitial_On_Dark() = SwitchHost(HaTheme.Dark) {
    RemoteHaToggleSwitch(
        initiallyOn = true,
        activeAccent = PreviewActiveAccent.rc,
        inactiveAccent = PreviewInactiveAccent.rc,
    )
}
