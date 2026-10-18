@file:Suppress("COMPOSE_APPLIER_CALL_MISMATCH", "RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize as uiFillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.FixedHaClock
import ee.schimke.ha.rc.LocalHaClock
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.androidXExperimental
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaSpace
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ProvideHaUberTheme
import java.time.ZoneOffset
import java.time.ZonedDateTime

// ---------------------------------------------------------------------------------------------
// Uber theme coverage sheets (card-component-system.md §5): every card style over the cards the
// showcase in UberThemePreviews.kt leaves out — the three 270° control dials, and the graph,
// keypad and media cards — so a reviewer sees each family's own dial and chart designs.
// ---------------------------------------------------------------------------------------------

private val SheetNow: ZonedDateTime = ZonedDateTime.of(2026, 5, 5, 10, 8, 0, 0, ZoneOffset.UTC)

/** Each theme in the mode it is shown in: its only mode, else light. */
private fun HaUberTheme.sheetDark(): Boolean = light == null

@Composable
private fun UberSheet(
  theme: HaUberTheme,
  cards: List<Pair<String, HaSnapshot>>,
  dark: Boolean = theme.sheetDark(),
) {
  Box(Modifier.uiFillMaxSize().background(theme.palette(dark).dashboardBackground).padding(8.dp)) {
    CapturingRemoteContentPreview(profile = androidXExperimental) {
      CompositionLocalProvider(LocalHaClock provides FixedHaClock(SheetNow)) {
        ProvideCardRegistry(defaultRegistry()) {
          ProvideHaUberTheme(theme, dark) {
            RemoteColumn(
              modifier = RemoteModifier.fillMaxWidth(),
              verticalArrangement = RemoteArrangement.spacedBy(HaSpace.M.rdp),
            ) {
              cards.forEach { (json, snapshot) ->
                RenderChild(card(json), snapshot, RemoteModifier.fillMaxWidth())
              }
            }
          }
        }
      }
    }
  }
}

private val Dials =
  listOf(
    """{"type":"thermostat","entity":"climate.living_room"}""" to Fixtures.thermostat,
    """{"type":"humidifier","entity":"humidifier.bedroom"}""" to Fixtures.humidifier,
    """{"type":"light","entity":"light.kitchen"}""" to Fixtures.brightLight,
  )

private val Cards =
  listOf(
    """{"type":"history-graph","title":"Temperature","hours_to_show":24,
        "entities":["sensor.outside_temp","sensor.upstairs_temp"]}""" to
      Fixtures.temperatureHistory,
    """{"type":"statistics-graph","title":"Power","period":"hour","stat_types":["mean"],
        "chart_type":"bar","entities":["sensor.house_power","sensor.solar_power"]}""" to
      Fixtures.energyStatistics,
    """{"type":"media-control","entity":"media_player.office_speaker"}""" to Fixtures.mediaPlaying,
    """{"type":"alarm-panel","entity":"alarm_control_panel.house",
        "states":["arm_away","arm_home"]}""" to
      Fixtures.alarmDisarmed,
  )

@Preview(name = "uber dials: Classic", widthDp = 360, heightDp = 900)
@Composable
fun UberDials_Classic() = UberSheet(HaUberTheme.Classic, Dials)

@Preview(name = "uber dials: Expressive", widthDp = 360, heightDp = 900)
@Composable
fun UberDials_Expressive() = UberSheet(HaUberTheme.Expressive, Dials)

@Preview(name = "uber dials: Trinitron", widthDp = 360, heightDp = 900)
@Composable
fun UberDials_Trinitron() = UberSheet(HaUberTheme.Trinitron, Dials)

@Preview(name = "uber dials: LCD", widthDp = 360, heightDp = 900)
@Composable
fun UberDials_Lcd() = UberSheet(HaUberTheme.Lcd, Dials)

@Preview(name = "uber dials: Braun", widthDp = 360, heightDp = 900)
@Composable
fun UberDials_Braun() = UberSheet(HaUberTheme.Braun, Dials)

@Preview(name = "uber dials: Nixie", widthDp = 360, heightDp = 900)
@Composable
fun UberDials_Nixie() = UberSheet(HaUberTheme.Nixie, Dials)

@Preview(name = "uber dials: Console", widthDp = 360, heightDp = 900)
@Composable
fun UberDials_Console() = UberSheet(HaUberTheme.Console, Dials)

@Preview(name = "uber cards: Classic", widthDp = 381, heightDp = 1000)
@Composable
fun UberCards_Classic() = UberSheet(HaUberTheme.Classic, Cards)

@Preview(name = "uber cards: Expressive", widthDp = 381, heightDp = 1000)
@Composable
fun UberCards_Expressive() = UberSheet(HaUberTheme.Expressive, Cards)

@Preview(name = "uber cards: Trinitron", widthDp = 381, heightDp = 1000)
@Composable
fun UberCards_Trinitron() = UberSheet(HaUberTheme.Trinitron, Cards)

@Preview(name = "uber cards: LCD", widthDp = 381, heightDp = 1000)
@Composable
fun UberCards_Lcd() = UberSheet(HaUberTheme.Lcd, Cards)

@Preview(name = "uber cards: Braun", widthDp = 381, heightDp = 1000)
@Composable
fun UberCards_Braun() = UberSheet(HaUberTheme.Braun, Cards)

@Preview(name = "uber cards: Nixie", widthDp = 381, heightDp = 1000)
@Composable
fun UberCards_Nixie() = UberSheet(HaUberTheme.Nixie, Cards)

@Preview(name = "uber cards: Console", widthDp = 381, heightDp = 1000)
@Composable
fun UberCards_Console() = UberSheet(HaUberTheme.Console, Cards)

@Preview(name = "uber dials: IKEA Showroom (light)", widthDp = 360, heightDp = 900)
@Composable
fun UberDials_Ikea_Light() = UberSheet(HaUberTheme.Ikea, Dials)

@Preview(name = "uber dials: IKEA Showroom (dark)", widthDp = 360, heightDp = 900)
@Composable
fun UberDials_Ikea_Dark() = UberSheet(HaUberTheme.Ikea, Dials, dark = true)

@Preview(name = "uber cards: IKEA Showroom (light)", widthDp = 381, heightDp = 1000)
@Composable
fun UberCards_Ikea_Light() = UberSheet(HaUberTheme.Ikea, Cards)

@Preview(name = "uber cards: IKEA Showroom (dark)", widthDp = 381, heightDp = 1000)
@Composable
fun UberCards_Ikea_Dark() = UberSheet(HaUberTheme.Ikea, Cards, dark = true)
