@file:Suppress("RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.androidXExperimental
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ProvideHaUberTheme
import ee.schimke.rc.m3.LocalRcAmbientInitial

/**
 * Cards as a low-power ambient display draws them: the host's ambient flag set, so every theme role
 * takes the ambient palette (black surfaces, light grey text) and the Expressive parts their
 * outlines. The same documents draw their usual look when the host clears the flag;
 * docs/architecture/ambient-mode.md.
 */
@Composable
private fun Ambient(
  theme: HaUberTheme,
  darkTheme: Boolean,
  card: String,
  snapshot: HaSnapshot,
) =
  CapturingRemoteContentPreview(profile = androidXExperimental) {
    CompositionLocalProvider(LocalRcAmbientInitial provides true) {
      ProvideCardRegistry(defaultRegistry()) {
        ProvideHaUberTheme(theme, darkTheme) { RenderChild(card = card(card), snapshot = snapshot) }
      }
    }
  }

/** A light theme's tile: its card goes black and its dark text light grey. */
@Preview(
  name = "ambient — tile, classic light",
  showBackground = false,
  widthDp = 187,
  heightDp = 56,
)
@Composable
fun Ambient_Tile_ClassicLight() =
  Ambient(
    HaUberTheme.Classic,
    darkTheme = false,
    """{"type":"tile","entity":"light.kitchen"}""",
    Fixtures.kitchenLight,
  )

/** An entities card in a light theme: rows, dividers and secondary text on black. */
@Preview(
  name = "ambient — entities, classic light",
  showBackground = false,
  widthDp = 320,
  heightDp = 200,
)
@Composable
fun Ambient_Entities_ClassicLight() =
  Ambient(
    HaUberTheme.Classic,
    darkTheme = false,
    """{"type":"entities","title":"Home","entities":["sensor.living_room","light.kitchen","switch.coffee_maker","lock.front_door"]}""",
    Fixtures.mixed,
  )

/** The Expressive tile slider: the card black, the slider a thin line and its handle. */
@Preview(
  name = "ambient — tile brightness, expressive",
  showBackground = false,
  widthDp = 187,
  heightDp = 112,
)
@Composable
fun Ambient_Tile_Expressive() =
  Ambient(
    HaUberTheme.Expressive,
    darkTheme = false,
    """{"type":"tile","entity":"light.kitchen","color":"amber","features":[{"type":"light-brightness"}]}""",
    Fixtures.kitchenLight,
  )
