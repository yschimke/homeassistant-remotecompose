@file:Suppress("COMPOSE_APPLIER_CALL_MISMATCH", "RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import ee.schimke.ha.rc.components.HaUberTheme

// ---------------------------------------------------------------------------------------------
// The text cards under every uber theme (docs/design/everyday-cards.md §3–4): a heading, a
// subtitle, and a titled note with a heading, bullets and a paragraph, so each family's heading
// treatment and markdown material read on their own.
// ---------------------------------------------------------------------------------------------

internal val EverydayTextCards =
  listOf(
    """{"type":"heading","heading":"Downstairs"}""" to Fixtures.livingRoomTemp,
    """{"type":"heading","heading":"Kitchen","heading_style":"subtitle"}""" to
      Fixtures.livingRoomTemp,
    """{"type":"markdown","title":"House notes","content":"## Tonight\n- Dishwasher running\n- Back door locked\n\nTemperature is **normal**."}""" to
      Fixtures.livingRoomTemp,
  )

@Preview(name = "uber text cards", widthDp = 360, heightDp = 400)
@Composable
fun UberEverydayText(@PreviewParameter(UberThemesProvider::class) theme: HaUberTheme) =
  UberSheet(theme, EverydayTextCards)
