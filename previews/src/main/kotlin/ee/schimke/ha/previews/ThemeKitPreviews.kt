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
import ee.schimke.ha.rc.components.RemoteHaLiveLockGlyph
import ee.schimke.ha.rc.components.RemoteHaLockRow
import ee.schimke.ha.rc.components.RemoteHaMeasurement
import ee.schimke.ha.rc.components.RemoteHaToggleSwitchByProgress
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
              // Switches and keys are slots too: a kit's own or the shared token switch.
              RenderChild(
                card(
                  """{"type":"entities","title":"Living Room","entities":[
                      "sensor.living_room","light.kitchen","switch.coffee_maker"]}"""
                ),
                Fixtures.mixed,
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
 * unlocked, beside a dial and a switch, in each theme's palette and grammar. Bespoke themes draw it
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
    // The switch is a slot: each theme's own, its family's, or the shared token switch.
    RemoteHaToggleSwitchByProgress(
      progress = 1f.rf,
      activeAccent = ee.schimke.ha.rc.components.HaStatusColors.None.rc,
      inactiveAccent = palette.divider,
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

/**
 * Keys as a slot: the same glance card — each entity a key — under bespoke themes with their own
 * key mounts (Console's machined keys, IKEA's moulded paddles), Classic's flat keys, and the kits,
 * whose keys come from the grammar (Engineered's outlined rims; none on a filled grammar).
 */
@Preview(name = "kit: keys across themes", widthDp = 360, heightDp = 630)
@Composable
fun Kit_Keys() {
  val themes =
    listOf(
      HaUberTheme.Classic to false,
      HaUberTheme.Console to true,
      HaUberTheme.Ikea to false,
      HaUberTheme.Aurora to false,
      HaUberTheme.Engineered to false,
      HaUberTheme.Cockpit to true,
    )
  Box(Modifier.uiFillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF808285))) {
    CapturingRemoteContentPreview(profile = androidXExperimental) {
      CompositionLocalProvider(LocalHaClock provides FixedHaClock(KitNow)) {
        ProvideCardRegistry(defaultRegistry()) {
          RemoteColumn(
            modifier = RemoteModifier.fillMaxWidth().padding(HaSpace.S.rdp),
            verticalArrangement = RemoteArrangement.spacedBy(HaSpace.S.rdp),
          ) {
            for ((theme, dark) in themes) {
              ProvideHaUberTheme(theme, dark) {
                RenderChild(
                  card(
                    """{"type":"glance","entities":[
                        "light.kitchen","switch.coffee_maker","lock.front_door"]}"""
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
}

/**
 * One card under several themes, [columns] to a row: each cell is the card under its own theme and
 * mode, on that theme's dashboard colour, so slot drawings can be compared side by side.
 */
@Composable
private fun KitCardSheet(
  themes: List<Pair<HaUberTheme, Boolean>>,
  columns: Int,
  cards: List<String>,
  snapshot: ee.schimke.ha.model.HaSnapshot,
) {
  Box(Modifier.uiFillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF808285))) {
    CapturingRemoteContentPreview(profile = androidXExperimental) {
      CompositionLocalProvider(LocalHaClock provides FixedHaClock(KitNow)) {
        ProvideCardRegistry(defaultRegistry()) {
          RemoteColumn(
            modifier = RemoteModifier.fillMaxWidth().padding(HaSpace.S.rdp),
            verticalArrangement = RemoteArrangement.spacedBy(HaSpace.S.rdp),
          ) {
            for (row in themes.chunked(columns)) {
              RemoteRow(modifier = RemoteModifier.fillMaxWidth()) {
                row.forEachIndexed { index, (theme, dark) ->
                  ProvideHaUberTheme(theme, dark) {
                    RemoteColumn(
                      modifier =
                        RemoteModifier.weight(1f)
                          .padding(end = if (index < row.size - 1) HaSpace.S.rdp else 0.rdp),
                      verticalArrangement = RemoteArrangement.spacedBy(HaSpace.S.rdp),
                    ) {
                      for (json in cards) {
                        RenderChild(card(json), snapshot, RemoteModifier.fillMaxWidth())
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

/**
 * The control dial as slots: the same thermostat under bespoke themes, which keep their own ring
 * (Classic's arc, Console's set-point knob), and the kits, whose rings come from their grammar —
 * Engineered's segments, Cockpit's needle, Aurora's gradient halo, EV Dash's thin arc.
 */
@Preview(name = "kit: dials across themes", widthDp = 360, heightDp = 640)
@Composable
fun Kit_Dials() =
  KitCardSheet(
    listOf(
      HaUberTheme.Classic to false,
      HaUberTheme.Console to true,
      HaUberTheme.Engineered to false,
      HaUberTheme.Cockpit to true,
    ),
    columns = 2,
    cards = listOf("""{"type":"thermostat","entity":"climate.living_room"}"""),
    snapshot = Fixtures.thermostat,
  )

/**
 * The dial's layout as a slot too: Cupertino keeps its bar with the steppers beside it, Expressive
 * its own ring; Aurora and EV Dash draw theirs from their grammar.
 */
@Preview(name = "kit: dials across themes (soft)", widthDp = 360, heightDp = 640)
@Composable
fun Kit_DialsSoft() =
  KitCardSheet(
    listOf(
      HaUberTheme.Expressive to false,
      HaUberTheme.Cupertino to false,
      HaUberTheme.Aurora to true,
      HaUberTheme.EvDash to true,
    ),
    columns = 2,
    cards = listOf("""{"type":"thermostat","entity":"climate.living_room"}"""),
    snapshot = Fixtures.thermostat,
  )

/**
 * Media transport keys as a slot: Console's keys with pilot lamps, IKEA's moulded keys, and the
 * kits' keys from their grammar — Engineered's outlined rims, Cockpit's bare icons on a filled one.
 */
@Preview(name = "kit: media keys across themes", widthDp = 360, heightDp = 640)
@Composable
fun Kit_MediaKeys() =
  KitCardSheet(
    listOf(
      HaUberTheme.Console to true,
      HaUberTheme.Ikea to false,
      HaUberTheme.Engineered to false,
      HaUberTheme.Cockpit to true,
    ),
    columns = 1,
    cards = listOf("""{"type":"media-control","entity":"media_player.office_speaker"}"""),
    snapshot = Fixtures.mediaPlaying,
  )

/**
 * Button icon chips as a slot: Expressive's shape morph (borrowed by Aurora), Console's key face,
 * IKEA's moulded key, Classic's tinted chip and Engineered's rimmed one.
 */
@Preview(name = "kit: button chips across themes", widthDp = 360, heightDp = 450)
@Composable
fun Kit_ButtonChips() =
  KitCardSheet(
    listOf(
      HaUberTheme.Classic to false,
      HaUberTheme.Expressive to false,
      HaUberTheme.Console to true,
      HaUberTheme.Ikea to false,
      HaUberTheme.Aurora to true,
      HaUberTheme.Engineered to false,
    ),
    columns = 3,
    cards =
      listOf(
        """{"type":"button","entity":"light.kitchen","name":"Kitchen","show_state":true}""",
        """{"type":"button","entity":"switch.coffee_maker","name":"Coffee","show_state":true}""",
      ),
    snapshot = Fixtures.mixed,
  )

/**
 * Text surfaces as a slot: a heading and a markdown note under bespoke themes (their own
 * treatments) and kits (from their grammar — Engineered's ruled heading and leading bar, Cockpit's
 * index dot, Aurora's tonal capsule, EV Dash's plain line).
 */
@Preview(name = "kit: text surfaces across themes", widthDp = 360, heightDp = 560)
@Composable
fun Kit_TextSurfaces() =
  KitCardSheet(
    listOf(
      HaUberTheme.Classic to false,
      HaUberTheme.Braun to false,
      HaUberTheme.Engineered to false,
      HaUberTheme.Cockpit to true,
      HaUberTheme.Aurora to true,
      HaUberTheme.EvDash to true,
    ),
    columns = 2,
    cards =
      listOf(
        """{"type":"heading","heading":"Downstairs"}""",
        """{"type":"markdown","title":"Notes","content":"Welcome home.\nTemperature is normal."}""",
      ),
    snapshot = Fixtures.mixed,
  )

/** History sparklines as a slot: the bespoke trace (Classic) and each kit's from its grammar. */
@Preview(name = "kit: history across themes", widthDp = 360, heightDp = 420)
@Composable
fun Kit_History() =
  KitCardSheet(
    listOf(
      HaUberTheme.Classic to false,
      HaUberTheme.Engineered to false,
      HaUberTheme.Cockpit to true,
      HaUberTheme.Aurora to true,
    ),
    columns = 2,
    cards =
      listOf(
        """{"type":"history-graph","title":"Temperature","hours_to_show":24,
            "entities":["sensor.outside_temp","sensor.upstairs_temp"]}"""
      ),
    snapshot = Fixtures.temperatureHistory,
  )

/** Statistics bars as a slot: the bespoke bars (Classic) and each kit's from its grammar. */
@Preview(name = "kit: statistics across themes", widthDp = 360, heightDp = 420)
@Composable
fun Kit_Statistics() =
  KitCardSheet(
    listOf(
      HaUberTheme.Classic to false,
      HaUberTheme.Engineered to false,
      HaUberTheme.Cockpit to true,
      HaUberTheme.Aurora to true,
    ),
    columns = 2,
    cards =
      listOf(
        """{"type":"statistics-graph","title":"Power","period":"hour","stat_types":["mean"],
            "entities":["sensor.house_power","sensor.solar_power"]}"""
      ),
    snapshot = Fixtures.energyStatistics,
  )
