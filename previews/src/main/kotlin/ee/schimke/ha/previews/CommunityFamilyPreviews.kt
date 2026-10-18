@file:Suppress("COMPOSE_APPLIER_CALL_MISMATCH", "RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.ha.rc.FixedHaClock
import ee.schimke.ha.rc.LocalHaClock
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaUberTheme

// Individually named entries publish PNG + .rc through the existing preview catalog pipeline.
// The same realistic fixtures as the established families make comparisons meaningful.

@Preview(
  name = "community: Mushroom (light)",
  widthDp = 360,
  heightDp = 680,
  device = "spec:width=360dp,height=680dp,dpi=160",
)
@Composable
fun Community_Mushroom_Light() = UberShowcase(HaUberTheme.Mushroom, darkTheme = false)

@Preview(
  name = "community: Mushroom (dark)",
  widthDp = 360,
  heightDp = 680,
  device = "spec:width=360dp,height=680dp,dpi=160",
)
@Composable
fun Community_Mushroom_Dark() = UberShowcase(HaUberTheme.Mushroom, darkTheme = true)

@Preview(
  name = "community dials: Mushroom",
  widthDp = 360,
  heightDp = 600,
  device = "spec:width=360dp,height=600dp,dpi=160",
)
@Composable
fun CommunityDials_Mushroom() = UberSheet(HaUberTheme.Mushroom, Dials.take(2))

@Preview(
  name = "community cards: Mushroom",
  widthDp = 381,
  heightDp = 600,
  device = "spec:width=381dp,height=600dp,dpi=160",
)
@Composable
fun CommunityCards_Mushroom() = UberSheet(HaUberTheme.Mushroom, Cards.take(2))

@Preview(
  name = "community: Bubble (light)",
  widthDp = 360,
  heightDp = 680,
  device = "spec:width=360dp,height=680dp,dpi=160",
)
@Composable
fun Community_Bubble_Light() = UberShowcase(HaUberTheme.Bubble, darkTheme = false)

@Preview(
  name = "community: Bubble (dark)",
  widthDp = 360,
  heightDp = 680,
  device = "spec:width=360dp,height=680dp,dpi=160",
)
@Composable
fun Community_Bubble_Dark() = UberShowcase(HaUberTheme.Bubble, darkTheme = true)

@Preview(
  name = "community dials: Bubble",
  widthDp = 360,
  heightDp = 600,
  device = "spec:width=360dp,height=600dp,dpi=160",
)
@Composable
fun CommunityDials_Bubble() = UberSheet(HaUberTheme.Bubble, Dials.take(2))

@Preview(
  name = "community cards: Bubble",
  widthDp = 381,
  heightDp = 600,
  device = "spec:width=381dp,height=600dp,dpi=160",
)
@Composable
fun CommunityCards_Bubble() = UberSheet(HaUberTheme.Bubble, Cards.take(2))

@Preview(
  name = "community: Minimalist (light)",
  widthDp = 360,
  heightDp = 680,
  device = "spec:width=360dp,height=680dp,dpi=160",
)
@Composable
fun Community_Minimalist_Light() = UberShowcase(HaUberTheme.Minimalist, darkTheme = false)

@Preview(
  name = "community: Minimalist (dark)",
  widthDp = 360,
  heightDp = 680,
  device = "spec:width=360dp,height=680dp,dpi=160",
)
@Composable
fun Community_Minimalist_Dark() = UberShowcase(HaUberTheme.Minimalist, darkTheme = true)

@Preview(
  name = "community dials: Minimalist",
  widthDp = 360,
  heightDp = 600,
  device = "spec:width=360dp,height=600dp,dpi=160",
)
@Composable
fun CommunityDials_Minimalist() = UberSheet(HaUberTheme.Minimalist, Dials.take(2))

@Preview(
  name = "community cards: Minimalist",
  widthDp = 381,
  heightDp = 600,
  device = "spec:width=381dp,height=600dp,dpi=160",
)
@Composable
fun CommunityCards_Minimalist() = UberSheet(HaUberTheme.Minimalist, Cards.take(2))

@Preview(
  name = "community: Cupertino (light)",
  widthDp = 360,
  heightDp = 680,
  device = "spec:width=360dp,height=680dp,dpi=160",
)
@Composable
fun Community_Cupertino_Light() = UberShowcase(HaUberTheme.Cupertino, darkTheme = false)

@Preview(
  name = "community: Cupertino (dark)",
  widthDp = 360,
  heightDp = 680,
  device = "spec:width=360dp,height=680dp,dpi=160",
)
@Composable
fun Community_Cupertino_Dark() = UberShowcase(HaUberTheme.Cupertino, darkTheme = true)

@Preview(
  name = "community dials: Cupertino",
  widthDp = 360,
  heightDp = 600,
  device = "spec:width=360dp,height=600dp,dpi=160",
)
@Composable
fun CommunityDials_Cupertino() = UberSheet(HaUberTheme.Cupertino, Dials.take(2))

@Preview(
  name = "community cards: Cupertino",
  widthDp = 381,
  heightDp = 600,
  device = "spec:width=381dp,height=600dp,dpi=160",
)
@Composable
fun CommunityCards_Cupertino() = UberSheet(HaUberTheme.Cupertino, Cards.take(2))

@Preview(
  name = "community: Catppuccin (light)",
  widthDp = 360,
  heightDp = 680,
  device = "spec:width=360dp,height=680dp,dpi=160",
)
@Composable
fun Community_Catppuccin_Light() = UberShowcase(HaUberTheme.Catppuccin, darkTheme = false)

@Preview(
  name = "community: Catppuccin (dark)",
  widthDp = 360,
  heightDp = 680,
  device = "spec:width=360dp,height=680dp,dpi=160",
)
@Composable
fun Community_Catppuccin_Dark() = UberShowcase(HaUberTheme.Catppuccin, darkTheme = true)

@Preview(
  name = "community dials: Catppuccin",
  widthDp = 360,
  heightDp = 600,
  device = "spec:width=360dp,height=600dp,dpi=160",
)
@Composable
fun CommunityDials_Catppuccin() = UberSheet(HaUberTheme.Catppuccin, Dials.take(2))

@Preview(
  name = "community cards: Catppuccin",
  widthDp = 381,
  heightDp = 600,
  device = "spec:width=381dp,height=600dp,dpi=160",
)
@Composable
fun CommunityCards_Catppuccin() = UberSheet(HaUberTheme.Catppuccin, Cards.take(2))

@Preview(
  name = "community: Obsidian (light)",
  widthDp = 360,
  heightDp = 680,
  device = "spec:width=360dp,height=680dp,dpi=160",
)
@Composable
fun Community_Obsidian_Light() = UberShowcase(HaUberTheme.Obsidian, darkTheme = false)

@Preview(
  name = "community: Obsidian (dark)",
  widthDp = 360,
  heightDp = 680,
  device = "spec:width=360dp,height=680dp,dpi=160",
)
@Composable
fun Community_Obsidian_Dark() = UberShowcase(HaUberTheme.Obsidian, darkTheme = true)

@Preview(
  name = "community dials: Obsidian",
  widthDp = 360,
  heightDp = 600,
  device = "spec:width=360dp,height=600dp,dpi=160",
)
@Composable
fun CommunityDials_Obsidian() = UberSheet(HaUberTheme.Obsidian, Dials.take(2))

@Preview(
  name = "community cards: Obsidian",
  widthDp = 381,
  heightDp = 600,
  device = "spec:width=381dp,height=600dp,dpi=160",
)
@Composable
fun CommunityCards_Obsidian() = UberSheet(HaUberTheme.Obsidian, Cards.take(2))

@Preview(
  name = "community: Noctis (light)",
  widthDp = 360,
  heightDp = 680,
  device = "spec:width=360dp,height=680dp,dpi=160",
)
@Composable
fun Community_Noctis_Light() = UberShowcase(HaUberTheme.Noctis, darkTheme = false)

@Preview(
  name = "community: Noctis (dark)",
  widthDp = 360,
  heightDp = 680,
  device = "spec:width=360dp,height=680dp,dpi=160",
)
@Composable
fun Community_Noctis_Dark() = UberShowcase(HaUberTheme.Noctis, darkTheme = true)

@Preview(
  name = "community dials: Noctis",
  widthDp = 360,
  heightDp = 600,
  device = "spec:width=360dp,height=600dp,dpi=160",
)
@Composable
fun CommunityDials_Noctis() = UberSheet(HaUberTheme.Noctis, Dials.take(2))

@Preview(
  name = "community cards: Noctis",
  widthDp = 381,
  heightDp = 600,
  device = "spec:width=381dp,height=600dp,dpi=160",
)
@Composable
fun CommunityCards_Noctis() = UberSheet(HaUberTheme.Noctis, Cards.take(2))

@Preview(
  name = "community: Lcars (light)",
  widthDp = 360,
  heightDp = 680,
  device = "spec:width=360dp,height=680dp,dpi=160",
)
@Composable
fun Community_Lcars_Light() = UberShowcase(HaUberTheme.Lcars, darkTheme = false)

@Preview(
  name = "community: Lcars (dark)",
  widthDp = 360,
  heightDp = 680,
  device = "spec:width=360dp,height=680dp,dpi=160",
)
@Composable
fun Community_Lcars_Dark() = UberShowcase(HaUberTheme.Lcars, darkTheme = true)

@Preview(
  name = "community dials: Lcars",
  widthDp = 360,
  heightDp = 600,
  device = "spec:width=360dp,height=600dp,dpi=160",
)
@Composable
fun CommunityDials_Lcars() = UberSheet(HaUberTheme.Lcars, Dials.take(2))

@Preview(
  name = "community cards: Lcars",
  widthDp = 381,
  heightDp = 600,
  device = "spec:width=381dp,height=600dp,dpi=160",
)
@Composable
fun CommunityCards_Lcars() = UberSheet(HaUberTheme.Lcars, Cards.take(2))

@Preview(
  name = "community: Arctic (light)",
  widthDp = 360,
  heightDp = 680,
  device = "spec:width=360dp,height=680dp,dpi=160",
)
@Composable
fun Community_Arctic_Light() = UberShowcase(HaUberTheme.Arctic, darkTheme = false)

@Preview(
  name = "community: Arctic (dark)",
  widthDp = 360,
  heightDp = 680,
  device = "spec:width=360dp,height=680dp,dpi=160",
)
@Composable
fun Community_Arctic_Dark() = UberShowcase(HaUberTheme.Arctic, darkTheme = true)

@Preview(
  name = "community dials: Arctic",
  widthDp = 360,
  heightDp = 600,
  device = "spec:width=360dp,height=600dp,dpi=160",
)
@Composable
fun CommunityDials_Arctic() = UberSheet(HaUberTheme.Arctic, Dials.take(2))

@Preview(
  name = "community cards: Arctic",
  widthDp = 381,
  heightDp = 600,
  device = "spec:width=381dp,height=600dp,dpi=160",
)
@Composable
fun CommunityCards_Arctic() = UberSheet(HaUberTheme.Arctic, Cards.take(2))

@Preview(
  name = "community: Aeon (light)",
  widthDp = 360,
  heightDp = 680,
  device = "spec:width=360dp,height=680dp,dpi=160",
)
@Composable
fun Community_Aeon_Light() = UberShowcase(HaUberTheme.Aeon, darkTheme = false)

@Preview(
  name = "community: Aeon (dark)",
  widthDp = 360,
  heightDp = 680,
  device = "spec:width=360dp,height=680dp,dpi=160",
)
@Composable
fun Community_Aeon_Dark() = UberShowcase(HaUberTheme.Aeon, darkTheme = true)

@Preview(
  name = "community dials: Aeon",
  widthDp = 360,
  heightDp = 600,
  device = "spec:width=360dp,height=600dp,dpi=160",
)
@Composable
fun CommunityDials_Aeon() = UberSheet(HaUberTheme.Aeon, Dials.take(2))

@Preview(
  name = "community cards: Aeon",
  widthDp = 381,
  heightDp = 600,
  device = "spec:width=381dp,height=600dp,dpi=160",
)
@Composable
fun CommunityCards_Aeon() = UberSheet(HaUberTheme.Aeon, Cards.take(2))

@Composable
private fun CommunityCompact(theme: HaUberTheme) {
  Box(Modifier.fillMaxSize().background(theme.palette(true).dashboardBackground)) {
    androidx.compose.foundation.layout.Column(
      verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
    ) {
      androidx.compose.foundation.layout.Row(
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
      ) {
        CommunityWidgetCell(
          theme,
          card("""{"type":"sensor","entity":"sensor.living_room"}"""),
          Fixtures.mixed,
          96,
          96,
        )
        CommunityWidgetCell(
          theme,
          card("""{"type":"button","entity":"light.kitchen"}"""),
          Fixtures.kitchenLight,
          96,
          96,
        )
        CommunityWidgetCell(
          theme,
          card("""{"type":"gauge","entity":"sensor.repeater_battery"}"""),
          Fixtures.battery,
          96,
          96,
        )
      }
      CommunityWidgetCell(
        theme,
        card("""{"type":"thermostat","entity":"climate.living_room"}"""),
        Fixtures.thermostat,
        240,
        80,
      )
    }
  }
}

/** Each widget gets its real host constraints and document, as in the existing card matrix. */
@Composable
private fun CommunityWidgetCell(
  theme: HaUberTheme,
  config: ee.schimke.ha.model.CardConfig,
  snapshot: ee.schimke.ha.model.HaSnapshot,
  width: Int,
  height: Int,
) {
  Box(Modifier.size(width.dp, height.dp)) {
    ee.schimke.ha.rc.CachedCardPreview(
      cacheKey = "community-widget-${theme.id}-${config.raw}-$width-$height",
      profile = ee.schimke.ha.rc.androidXExperimentalWrap,
      modifier = Modifier.fillMaxSize(),
      card = config,
      snapshot = snapshot,
      playback = ee.schimke.ha.rc.CardPlayback.LauncherWidget,
    ) {
      CompositionLocalProvider(
        LocalHaClock provides FixedHaClock(java.time.ZonedDateTime.parse("2026-05-05T10:08:00Z"))
      ) {
        ee.schimke.ha.rc.LauncherWidgetContent(
          registry = defaultRegistry(),
          profile = ee.schimke.ha.rc.androidXExperimentalWrap,
          dark = true,
          widgetActions =
            ee.schimke.ha.rc.components.WidgetActionRegistry(
              snapshot.states.mapValues { (_, entity) ->
                ee.schimke.ha.rc.formatState(entity, snapshot)
              }
            ),
          card = config,
          snapshot = snapshot,
          captureSizeDp = ee.schimke.ha.rc.WidgetCaptureSizeDp(width, height),
          look =
            ee.schimke.ha.rc.LauncherLook(
              theme,
              ee.schimke.ha.rc.components.ThemeStyle.TerrazzoHome,
            ),
        )
      }
    }
  }
}

@Preview(
  name = "community compact: Mushroom",
  widthDp = 304,
  heightDp = 200,
  device = "spec:width=304dp,height=200dp,dpi=160",
)
@Composable
fun CommunityCompact_Mushroom() = CommunityCompact(HaUberTheme.Mushroom)

@Preview(
  name = "community compact: Bubble",
  widthDp = 304,
  heightDp = 200,
  device = "spec:width=304dp,height=200dp,dpi=160",
)
@Composable
fun CommunityCompact_Bubble() = CommunityCompact(HaUberTheme.Bubble)

@Preview(
  name = "community compact: Minimalist",
  widthDp = 304,
  heightDp = 200,
  device = "spec:width=304dp,height=200dp,dpi=160",
)
@Composable
fun CommunityCompact_Minimalist() = CommunityCompact(HaUberTheme.Minimalist)

@Preview(
  name = "community compact: Cupertino",
  widthDp = 304,
  heightDp = 200,
  device = "spec:width=304dp,height=200dp,dpi=160",
)
@Composable
fun CommunityCompact_Cupertino() = CommunityCompact(HaUberTheme.Cupertino)

@Preview(
  name = "community compact: Catppuccin",
  widthDp = 304,
  heightDp = 200,
  device = "spec:width=304dp,height=200dp,dpi=160",
)
@Composable
fun CommunityCompact_Catppuccin() = CommunityCompact(HaUberTheme.Catppuccin)

@Preview(
  name = "community compact: Obsidian",
  widthDp = 304,
  heightDp = 200,
  device = "spec:width=304dp,height=200dp,dpi=160",
)
@Composable
fun CommunityCompact_Obsidian() = CommunityCompact(HaUberTheme.Obsidian)

@Preview(
  name = "community compact: Noctis",
  widthDp = 304,
  heightDp = 200,
  device = "spec:width=304dp,height=200dp,dpi=160",
)
@Composable
fun CommunityCompact_Noctis() = CommunityCompact(HaUberTheme.Noctis)

@Preview(
  name = "community compact: Lcars",
  widthDp = 304,
  heightDp = 200,
  device = "spec:width=304dp,height=200dp,dpi=160",
)
@Composable
fun CommunityCompact_Lcars() = CommunityCompact(HaUberTheme.Lcars)

@Preview(
  name = "community compact: Arctic",
  widthDp = 304,
  heightDp = 200,
  device = "spec:width=304dp,height=200dp,dpi=160",
)
@Composable
fun CommunityCompact_Arctic() = CommunityCompact(HaUberTheme.Arctic)

@Preview(
  name = "community compact: Aeon",
  widthDp = 304,
  heightDp = 200,
  device = "spec:width=304dp,height=200dp,dpi=160",
)
@Composable
fun CommunityCompact_Aeon() = CommunityCompact(HaUberTheme.Aeon)

@Preview(
  name = "community light: Mushroom",
  widthDp = 360,
  heightDp = 320,
  device = "spec:width=360dp,height=320dp,dpi=160",
)
@Composable
fun CommunityLight_Mushroom() = UberSheet(HaUberTheme.Mushroom, Dials.takeLast(1))

@Preview(
  name = "community media and alarm: Mushroom",
  widthDp = 381,
  heightDp = 600,
  device = "spec:width=381dp,height=600dp,dpi=160",
)
@Composable
fun CommunityMedia_Mushroom() = UberSheet(HaUberTheme.Mushroom, Cards.takeLast(2))

@Preview(
  name = "community light: Bubble",
  widthDp = 360,
  heightDp = 320,
  device = "spec:width=360dp,height=320dp,dpi=160",
)
@Composable
fun CommunityLight_Bubble() = UberSheet(HaUberTheme.Bubble, Dials.takeLast(1))

@Preview(
  name = "community media and alarm: Bubble",
  widthDp = 381,
  heightDp = 600,
  device = "spec:width=381dp,height=600dp,dpi=160",
)
@Composable
fun CommunityMedia_Bubble() = UberSheet(HaUberTheme.Bubble, Cards.takeLast(2))

@Preview(
  name = "community light: Minimalist",
  widthDp = 360,
  heightDp = 320,
  device = "spec:width=360dp,height=320dp,dpi=160",
)
@Composable
fun CommunityLight_Minimalist() = UberSheet(HaUberTheme.Minimalist, Dials.takeLast(1))

@Preview(
  name = "community media and alarm: Minimalist",
  widthDp = 381,
  heightDp = 600,
  device = "spec:width=381dp,height=600dp,dpi=160",
)
@Composable
fun CommunityMedia_Minimalist() = UberSheet(HaUberTheme.Minimalist, Cards.takeLast(2))

@Preview(
  name = "community light: Cupertino",
  widthDp = 360,
  heightDp = 320,
  device = "spec:width=360dp,height=320dp,dpi=160",
)
@Composable
fun CommunityLight_Cupertino() = UberSheet(HaUberTheme.Cupertino, Dials.takeLast(1))

@Preview(
  name = "community media and alarm: Cupertino",
  widthDp = 381,
  heightDp = 600,
  device = "spec:width=381dp,height=600dp,dpi=160",
)
@Composable
fun CommunityMedia_Cupertino() = UberSheet(HaUberTheme.Cupertino, Cards.takeLast(2))

@Preview(
  name = "community light: Catppuccin",
  widthDp = 360,
  heightDp = 320,
  device = "spec:width=360dp,height=320dp,dpi=160",
)
@Composable
fun CommunityLight_Catppuccin() = UberSheet(HaUberTheme.Catppuccin, Dials.takeLast(1))

@Preview(
  name = "community media and alarm: Catppuccin",
  widthDp = 381,
  heightDp = 600,
  device = "spec:width=381dp,height=600dp,dpi=160",
)
@Composable
fun CommunityMedia_Catppuccin() = UberSheet(HaUberTheme.Catppuccin, Cards.takeLast(2))

@Preview(
  name = "community light: Obsidian",
  widthDp = 360,
  heightDp = 320,
  device = "spec:width=360dp,height=320dp,dpi=160",
)
@Composable
fun CommunityLight_Obsidian() = UberSheet(HaUberTheme.Obsidian, Dials.takeLast(1))

@Preview(
  name = "community media and alarm: Obsidian",
  widthDp = 381,
  heightDp = 600,
  device = "spec:width=381dp,height=600dp,dpi=160",
)
@Composable
fun CommunityMedia_Obsidian() = UberSheet(HaUberTheme.Obsidian, Cards.takeLast(2))

@Preview(
  name = "community light: Noctis",
  widthDp = 360,
  heightDp = 320,
  device = "spec:width=360dp,height=320dp,dpi=160",
)
@Composable
fun CommunityLight_Noctis() = UberSheet(HaUberTheme.Noctis, Dials.takeLast(1))

@Preview(
  name = "community media and alarm: Noctis",
  widthDp = 381,
  heightDp = 600,
  device = "spec:width=381dp,height=600dp,dpi=160",
)
@Composable
fun CommunityMedia_Noctis() = UberSheet(HaUberTheme.Noctis, Cards.takeLast(2))

@Preview(
  name = "community light: Lcars",
  widthDp = 360,
  heightDp = 320,
  device = "spec:width=360dp,height=320dp,dpi=160",
)
@Composable
fun CommunityLight_Lcars() = UberSheet(HaUberTheme.Lcars, Dials.takeLast(1))

@Preview(
  name = "community media and alarm: Lcars",
  widthDp = 381,
  heightDp = 600,
  device = "spec:width=381dp,height=600dp,dpi=160",
)
@Composable
fun CommunityMedia_Lcars() = UberSheet(HaUberTheme.Lcars, Cards.takeLast(2))

@Preview(
  name = "community light: Arctic",
  widthDp = 360,
  heightDp = 320,
  device = "spec:width=360dp,height=320dp,dpi=160",
)
@Composable
fun CommunityLight_Arctic() = UberSheet(HaUberTheme.Arctic, Dials.takeLast(1))

@Preview(
  name = "community media and alarm: Arctic",
  widthDp = 381,
  heightDp = 600,
  device = "spec:width=381dp,height=600dp,dpi=160",
)
@Composable
fun CommunityMedia_Arctic() = UberSheet(HaUberTheme.Arctic, Cards.takeLast(2))

@Preview(
  name = "community light: Aeon",
  widthDp = 360,
  heightDp = 320,
  device = "spec:width=360dp,height=320dp,dpi=160",
)
@Composable
fun CommunityLight_Aeon() = UberSheet(HaUberTheme.Aeon, Dials.takeLast(1))

@Preview(
  name = "community media and alarm: Aeon",
  widthDp = 381,
  heightDp = 600,
  device = "spec:width=381dp,height=600dp,dpi=160",
)
@Composable
fun CommunityMedia_Aeon() = UberSheet(HaUberTheme.Aeon, Cards.takeLast(2))
