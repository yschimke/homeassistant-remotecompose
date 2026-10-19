@file:Suppress("COMPOSE_APPLIER_CALL_MISMATCH", "RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import ee.schimke.ha.rc.components.HaUberTheme

// ---------------------------------------------------------------------------------------------
// The everyday cards under every uber theme. `scripts/card-usage.py` counts card types across the
// bundled dashboards: a sensor tile, a section heading, a markdown note and a history graph make up
// ~90% of the rendered cards (grids are layout, not cards), so these four decide how a dashboard
// looks far more than any dial does. One preview per theme, each in the mode its sheet uses.
// ---------------------------------------------------------------------------------------------

internal val CommonCards =
  listOf(
    """{"type":"heading","heading":"Living room"}""" to Fixtures.livingRoomTemp,
    """{"type":"tile","entity":"sensor.living_room"}""" to Fixtures.livingRoomTemp,
    """{"type":"markdown","content":"**Welcome home.** Temperature is normal."}""" to
      Fixtures.livingRoomTemp,
    """{"type":"history-graph","title":"Temperature","hours_to_show":24,
        "entities":["sensor.outside_temp","sensor.upstairs_temp"]}""" to
      Fixtures.temperatureHistory,
  )

/** Every uber theme, [HaUberTheme.Classic] first. */
class UberThemesProvider : PreviewParameterProvider<HaUberTheme> {
  override val values: Sequence<HaUberTheme> = HaUberTheme.all.asSequence()

  override fun getDisplayName(index: Int): String = HaUberTheme.all[index].id
}

@Preview(name = "uber common cards", widthDp = 360, heightDp = 560)
@Composable
fun UberCommon(@PreviewParameter(UberThemesProvider::class) theme: HaUberTheme) =
  UberSheet(theme, CommonCards)
