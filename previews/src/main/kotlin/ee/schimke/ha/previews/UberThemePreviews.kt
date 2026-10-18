@file:Suppress("COMPOSE_APPLIER_CALL_MISMATCH", "RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize as uiFillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.ha.rc.FixedHaClock
import ee.schimke.ha.rc.LocalHaClock
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.androidXExperimental
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaMeasureKind
import ee.schimke.ha.rc.components.HaPresentation
import ee.schimke.ha.rc.components.HaReading
import ee.schimke.ha.rc.components.HaSpace
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ProvideHaUberTheme
import ee.schimke.ha.rc.components.RemoteHaMeasurement
import ee.schimke.ha.rc.components.cardChrome
import ee.schimke.ha.rc.components.currentRemoteHaTheme
import java.time.ZoneOffset
import java.time.ZonedDateTime

// ---------------------------------------------------------------------------------------------
// Uber theme showcases (card-component-system.md §6): the same cards — a converted gauge, tile
// and entities card, plus an expanded thermometer, a battery and a humidity arc — under each uber
// theme, so a reviewer sees shape, type, meter form and ink change together. One document per
// preview, so each publishes its `.rc` sidecar.
// ---------------------------------------------------------------------------------------------

private val PreviewNow: ZonedDateTime = ZonedDateTime.of(2026, 5, 5, 10, 8, 0, 0, ZoneOffset.UTC)

@Composable
internal fun UberShowcase(theme: HaUberTheme, darkTheme: Boolean) {
  Box(
    Modifier.uiFillMaxSize().background(theme.palette(darkTheme).dashboardBackground).padding(8.dp)
  ) {
    CapturingRemoteContentPreview(profile = androidXExperimental) {
      CompositionLocalProvider(LocalHaClock provides FixedHaClock(PreviewNow)) {
        ProvideCardRegistry(defaultRegistry()) {
          ProvideHaUberTheme(theme, darkTheme) {
            RemoteColumn(
              modifier = RemoteModifier.fillMaxWidth(),
              verticalArrangement = RemoteArrangement.spacedBy(HaSpace.M.rdp),
            ) {
              RenderChild(
                card(
                  """{"type":"gauge","entity":"sensor.repeater_battery","name":"Battery %",
                      "min":0,"max":100,"severity":{"green":60,"yellow":30,"red":0}}"""
                ),
                Fixtures.battery,
                RemoteModifier.fillMaxWidth(),
              )
              RemoteRow(
                modifier = RemoteModifier.fillMaxWidth(),
                horizontalArrangement = RemoteArrangement.spacedBy(HaSpace.M.rdp),
              ) {
                MeasurementCard(RemoteModifier.weight(1f).height(196.rdp)) {
                  RemoteHaMeasurement(
                    HaReading.static("Living room", 21.5f, "°C", HaMeasureKind.Temperature),
                    HaPresentation.Expanded,
                  )
                }
                RemoteColumn(
                  modifier = RemoteModifier.weight(1f),
                  verticalArrangement = RemoteArrangement.spacedBy(HaSpace.M.rdp),
                ) {
                  MeasurementCard(RemoteModifier.fillMaxWidth()) {
                    RemoteHaMeasurement(
                      HaReading.static("Lock", 12f, "%", HaMeasureKind.Battery),
                      HaPresentation.Compact,
                    )
                  }
                  MeasurementCard(RemoteModifier.fillMaxWidth()) {
                    RemoteHaMeasurement(
                      HaReading.static("Bathroom", 64f, "%", HaMeasureKind.Humidity),
                      HaPresentation.Compact,
                    )
                  }
                  MeasurementCard(RemoteModifier.fillMaxWidth()) {
                    RemoteHaMeasurement(
                      HaReading.static("Outside", 36f, "°C", HaMeasureKind.Temperature),
                      HaPresentation.Compact,
                    )
                  }
                }
              }
              RenderChild(
                card("""{"type":"tile","entity":"light.kitchen"}"""),
                Fixtures.kitchenLight,
                RemoteModifier.fillMaxWidth(),
              )
              RenderChild(
                card(
                  """{"type":"entities","title":"Living Room","entities":[
                      "sensor.living_room","light.kitchen","switch.coffee_maker"]}"""
                ),
                Fixtures.mixed,
                RemoteModifier.fillMaxWidth(),
              )
            }
          }
        }
      }
    }
  }
}

/** A card surface for a bare measurement, drawn with the uber theme's chrome. */
@Composable
private fun MeasurementCard(modifier: RemoteModifier, content: @Composable () -> Unit) {
  val theme = currentRemoteHaTheme()
  RemoteBox(
    modifier = modifier.then(cardChrome(theme.cardBackground, theme.divider)).padding(HaSpace.M.rdp)
  ) {
    content()
  }
}

@Preview(name = "uber: Classic (light)", widthDp = 360, heightDp = 640, showBackground = false)
@Composable
fun Uber_Classic_Light() = UberShowcase(HaUberTheme.Classic, darkTheme = false)

@Preview(name = "uber: Material 3 Expressive (light)", widthDp = 360, heightDp = 640)
@Composable
fun Uber_Expressive_Light() = UberShowcase(HaUberTheme.Expressive, darkTheme = false)

@Preview(name = "uber: Material 3 Expressive (dark)", widthDp = 360, heightDp = 640)
@Composable
fun Uber_Expressive_Dark() = UberShowcase(HaUberTheme.Expressive, darkTheme = true)

@Preview(name = "uber: Trinitron", widthDp = 360, heightDp = 640)
@Composable
fun Uber_Trinitron() = UberShowcase(HaUberTheme.Trinitron, darkTheme = true)

@Preview(name = "uber: LCD (reflective)", widthDp = 360, heightDp = 640)
@Composable
fun Uber_Lcd_Light() = UberShowcase(HaUberTheme.Lcd, darkTheme = false)

@Preview(name = "uber: LCD (Indiglo backlight)", widthDp = 360, heightDp = 640)
@Composable
fun Uber_Lcd_Dark() = UberShowcase(HaUberTheme.Lcd, darkTheme = true)

@Preview(name = "uber: Braun (light)", widthDp = 360, heightDp = 640)
@Composable
fun Uber_Braun_Light() = UberShowcase(HaUberTheme.Braun, darkTheme = false)

@Preview(name = "uber: Braun (dark)", widthDp = 360, heightDp = 640)
@Composable
fun Uber_Braun_Dark() = UberShowcase(HaUberTheme.Braun, darkTheme = true)

@Preview(name = "uber: Nixie", widthDp = 360, heightDp = 640)
@Composable
fun Uber_Nixie() = UberShowcase(HaUberTheme.Nixie, darkTheme = true)

@Preview(name = "uber: Console", widthDp = 360, heightDp = 640)
@Composable
fun Uber_Console() = UberShowcase(HaUberTheme.Console, darkTheme = true)

/** Console's rotary controls: a thermostat as a knob in a lamp ring, beside a VU-meter gauge. */
@Preview(name = "uber: Console (desk)", widthDp = 360, heightDp = 420)
@Composable
fun Uber_Console_Desk() {
  val theme = HaUberTheme.Console
  Box(Modifier.uiFillMaxSize().background(theme.palette(true).dashboardBackground).padding(8.dp)) {
    CapturingRemoteContentPreview(profile = androidXExperimental) {
      CompositionLocalProvider(LocalHaClock provides FixedHaClock(PreviewNow)) {
        ProvideCardRegistry(defaultRegistry()) {
          ProvideHaUberTheme(theme, darkTheme = true) {
            RemoteColumn(
              modifier = RemoteModifier.fillMaxWidth(),
              verticalArrangement = RemoteArrangement.spacedBy(HaSpace.M.rdp),
            ) {
              RenderChild(
                card("""{"type":"thermostat","entity":"climate.living_room"}"""),
                Fixtures.thermostat,
                RemoteModifier.fillMaxWidth(),
              )
              RenderChild(
                card(
                  """{"type":"gauge","entity":"sensor.repeater_battery","name":"Level",
                      "min":0,"max":100,"severity":{"green":0,"yellow":60,"red":80}}"""
                ),
                Fixtures.battery,
                RemoteModifier.fillMaxWidth(),
              )
            }
          }
        }
      }
    }
  }
}

@Preview(name = "uber: IKEA Showroom (light)", widthDp = 360, heightDp = 900)
@Composable
fun Uber_Ikea_Light() = UberShowcase(HaUberTheme.Ikea, darkTheme = false)

@Preview(name = "uber: IKEA Showroom (dark)", widthDp = 360, heightDp = 900)
@Composable
fun Uber_Ikea_Dark() = UberShowcase(HaUberTheme.Ikea, darkTheme = true)

@Preview(
  name = "uber: Home Assistant (light)",
  widthDp = 360,
  heightDp = 900,
  device = "spec:width=360dp,height=900dp,dpi=160",
)
@Composable
fun Uber_HomeAssistant_Light() = UberShowcase(HaUberTheme.HomeAssistant, darkTheme = false)

@Preview(
  name = "uber: Home Assistant (dark)",
  widthDp = 360,
  heightDp = 900,
  device = "spec:width=360dp,height=900dp,dpi=160",
)
@Composable
fun Uber_HomeAssistant_Dark() = UberShowcase(HaUberTheme.HomeAssistant, darkTheme = true)
