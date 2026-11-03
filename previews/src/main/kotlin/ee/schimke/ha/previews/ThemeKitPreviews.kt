@file:Suppress("COMPOSE_APPLIER_CALL_MISMATCH", "RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize as uiFillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
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
import ee.schimke.ha.rc.components.HaType
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ProvideHaUberTheme
import ee.schimke.ha.rc.components.RemoteHaArcMeter
import ee.schimke.ha.rc.components.RemoteHaBatteryGlyph
import ee.schimke.ha.rc.components.RemoteHaLiveLockGlyph
import ee.schimke.ha.rc.components.RemoteHaLockRow
import ee.schimke.ha.rc.components.RemoteHaMeasurement
import ee.schimke.ha.rc.components.cardChrome
import ee.schimke.ha.rc.components.currentRemoteHaTheme
import ee.schimke.ha.rc.components.haTextStyle
import java.time.ZoneOffset
import java.time.ZonedDateTime

// ---------------------------------------------------------------------------------------------
// Theme kits (docs/architecture/theme-kits.md): looks made of tokens, with no drawings of their
// own. Each showcase plays converted cards that predate the kit and a "car doors" card built from
// RemoteHaLockRow, a component written once against the grammar — no per-theme code in either.
// ---------------------------------------------------------------------------------------------

private val KitNow: ZonedDateTime = ZonedDateTime.of(2026, 5, 5, 10, 8, 0, 0, ZoneOffset.UTC)

@Composable
private fun KitShowcase(theme: HaUberTheme, darkTheme: Boolean) {
  Box(
    Modifier.uiFillMaxSize().background(theme.palette(darkTheme).dashboardBackground).padding(8.dp)
  ) {
    CapturingRemoteContentPreview(profile = androidXExperimental) {
      CompositionLocalProvider(LocalHaClock provides FixedHaClock(KitNow)) {
        ProvideCardRegistry(defaultRegistry()) {
          ProvideHaUberTheme(theme, darkTheme) {
            RemoteColumn(
              modifier = RemoteModifier.fillMaxWidth(),
              verticalArrangement = RemoteArrangement.spacedBy(HaSpace.M.rdp),
            ) {
              CarDoorsCard()
              RenderChild(
                card(
                  """{"type":"gauge","entity":"sensor.repeater_battery","name":"Battery %",
                      "min":0,"max":100,"severity":{"green":60,"yellow":30,"red":0}}"""
                ),
                Fixtures.battery,
                RemoteModifier.fillMaxWidth(),
              )
              RemoteRow(modifier = RemoteModifier.fillMaxWidth()) {
                KitCard(RemoteModifier.weight(1f).padding(end = HaSpace.M.rdp).height(176.rdp)) {
                  RemoteHaMeasurement(
                    HaReading.static("Cabin", 21.5f, "°C", HaMeasureKind.Temperature),
                    HaPresentation.Expanded,
                  )
                }
                RemoteColumn(
                  modifier = RemoteModifier.weight(1f),
                  verticalArrangement = RemoteArrangement.spacedBy(HaSpace.M.rdp),
                ) {
                  KitCard(RemoteModifier.fillMaxWidth()) {
                    RemoteHaMeasurement(
                      HaReading.static("Range", 12f, "%", HaMeasureKind.Battery),
                      HaPresentation.Compact,
                    )
                  }
                  KitCard(RemoteModifier.fillMaxWidth()) {
                    RemoteHaMeasurement(
                      HaReading.static("Humidity", 64f, "%", HaMeasureKind.Humidity),
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
            }
          }
        }
      }
    }
  }
}

/** The car-lock card the kits never heard of: three locks, one card, any theme. */
@Composable
private fun CarDoorsCard() {
  val theme = currentRemoteHaTheme()
  KitCard(RemoteModifier.fillMaxWidth()) {
    RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(HaSpace.S.rdp)) {
      RemoteText(
        text = "Car".rs,
        color = theme.primaryText,
        fontSize = HaType.Title,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      RemoteHaLockRow(null, "Doors", "Locked", locked = true)
      RemoteHaLockRow(null, "Boot", "Unlocked", locked = false)
      RemoteHaLockRow(null, "Charge port", "Locked", locked = true)
    }
  }
}

@Composable
private fun KitCard(modifier: RemoteModifier, content: @Composable () -> Unit) {
  val theme = currentRemoteHaTheme()
  RemoteBox(
    modifier = modifier.then(cardChrome(theme.cardBackground, theme.divider)).padding(HaSpace.M.rdp)
  ) {
    content()
  }
}

@Preview(name = "kit: EV Dash (light)", widthDp = 360, heightDp = 640)
@Composable
fun Kit_EvDash_Light() = KitShowcase(HaUberTheme.EvDash, darkTheme = false)

@Preview(name = "kit: EV Dash (dark)", widthDp = 360, heightDp = 640)
@Composable
fun Kit_EvDash_Dark() = KitShowcase(HaUberTheme.EvDash, darkTheme = true)

@Preview(name = "kit: Aurora (light)", widthDp = 360, heightDp = 640)
@Composable
fun Kit_Aurora_Light() = KitShowcase(HaUberTheme.Aurora, darkTheme = false)

@Preview(name = "kit: Aurora (dark)", widthDp = 360, heightDp = 640)
@Composable
fun Kit_Aurora_Dark() = KitShowcase(HaUberTheme.Aurora, darkTheme = true)

@Preview(name = "kit: Engineered (light)", widthDp = 360, heightDp = 640)
@Composable
fun Kit_Engineered_Light() = KitShowcase(HaUberTheme.Engineered, darkTheme = false)

@Preview(name = "kit: Engineered (dark)", widthDp = 360, heightDp = 640)
@Composable
fun Kit_Engineered_Dark() = KitShowcase(HaUberTheme.Engineered, darkTheme = true)

@Preview(name = "kit: Cockpit", widthDp = 360, heightDp = 640)
@Composable
fun Kit_Cockpit() = KitShowcase(HaUberTheme.Cockpit, darkTheme = true)

/**
 * One component under every theme: the lock — written once against the grammar — locked and
 * unlocked, beside a dial, a battery, in each theme's palette and grammar. Bespoke themes draw it
 * from the grammar their family implies; kits from their own.
 */
@Preview(name = "kit: lock under every theme", widthDp = 360, heightDp = 516)
@Composable
fun Kit_LockEveryTheme() {
  Box(Modifier.uiFillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF808285))) {
    CapturingRemoteContentPreview(profile = androidXExperimental) {
      val themes = HaUberTheme.all
      RemoteColumn(
        modifier = RemoteModifier.fillMaxWidth().padding(HaSpace.S.rdp),
        verticalArrangement = RemoteArrangement.spacedBy(HaSpace.S.rdp),
      ) {
        for (row in themes.chunked(2)) {
          RemoteRow(modifier = RemoteModifier.fillMaxWidth()) {
            row.forEachIndexed { index, theme ->
              val dark = theme.light == null || index % 2 == 1
              ProvideHaUberTheme(theme, dark) {
                LockCell(
                  theme,
                  RemoteModifier.weight(1f).padding(end = if (index == 0) HaSpace.S.rdp else 0.rdp),
                )
              }
            }
            if (row.size == 1) RemoteBox(RemoteModifier.weight(1f)) {}
          }
        }
      }
    }
  }
}

@Composable
private fun LockCell(theme: HaUberTheme, modifier: RemoteModifier) {
  val palette = currentRemoteHaTheme()
  RemoteRow(
    modifier =
      modifier.then(cardChrome(palette.cardBackground, palette.divider)).padding(HaSpace.S.rdp),
    verticalAlignment = RemoteAlignment.CenterVertically,
  ) {
    RemoteHaLiveLockGlyph(null, locked = true, RemoteModifier.size(18.rdp, 23.rdp))
    RemoteBox(RemoteModifier.size(4.rdp, 4.rdp)) {}
    RemoteHaLiveLockGlyph(null, locked = false, RemoteModifier.size(18.rdp, 23.rdp))
    RemoteBox(RemoteModifier.size(6.rdp, 4.rdp)) {}
    RemoteHaArcMeter(
      0.62f.rf,
      ee.schimke.ha.rc.components.HaStatusColors.None.rc,
      RemoteModifier.size(34.rdp, 20.rdp),
      strokeWidthPx = 6f,
    )
    RemoteBox(RemoteModifier.size(6.rdp, 4.rdp)) {}
    RemoteHaBatteryGlyph(
      0.4f.rf,
      ee.schimke.ha.rc.components.HaStatusColors.Normal.rc,
      RemoteModifier.size(26.rdp, 13.rdp),
    )
    RemoteText(
      text = theme.id.rs,
      color = palette.secondaryText,
      fontSize = HaType.Micro,
      style = haTextStyle(),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = RemoteModifier.padding(start = HaSpace.S.rdp),
    )
  }
}
