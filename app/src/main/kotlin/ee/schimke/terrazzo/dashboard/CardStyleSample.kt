package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth as rcFillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CachedCardPreview
import ee.schimke.ha.rc.CardTemplatePlayback
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.androidXExperimentalWrap
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withEnhancedShutter
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ProvideCardStyle
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.cardPalette
import ee.schimke.terrazzo.ui.LocalIsDarkTheme
import ee.schimke.terrazzo.ui.LocalThemeStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One fixed tile card drawn in [cardStyle], on the app's palette and dark mode: what the card style
 * picker in Settings shows beside each style, so a style can be judged by a real card rather than
 * its name. Renders the way a dashboard slot does — the local design library's template where the
 * tile has one, else its converter — but from a frozen sample, so it never changes and needs no
 * session.
 */
@Composable
internal fun CardStyleSample(cardStyle: HaUberTheme, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val registry = remember { defaultRegistry().withEnhancedShutter() }
  val style = LocalThemeStyle.current
  val dark = LocalIsDarkTheme.current
  val haTheme = remember(style, dark, cardStyle) { cardStyle.cardPalette(style, dark) }
  // Plain CardDesigns: the local library only. The server's designs are fetched for the chosen
  // style alone, and reading them here would log the other styles as the server's gaps.
  val loadTemplate = {
    cardTemplateFor(
      context,
      SAMPLE_CARD,
      SAMPLE_SNAPSHOT,
      CardDesigns(),
      style,
      dark,
      registry,
      cardStyle,
    )
  }
  // The library captures a template the first time a key is asked for. Expanding the picker asks
  // for every style at once, so the samples load off the UI thread (as widget previews capture)
  // and hold their height until then. Previews load in place, so a render shows the card.
  val inspection = LocalInspectionMode.current
  val inPlace =
    remember(style, dark, cardStyle) { if (inspection) SampleTemplate(loadTemplate()) else null }
  val loaded by
    produceState(
      initialValue = inPlace,
      style,
      dark,
      cardStyle,
    ) {
      if (value == null) value = SampleTemplate(withContext(Dispatchers.Default) { loadTemplate() })
    }
  val template = loaded?.playback
  if (loaded == null) {
    Box(modifier.fillMaxWidth().height(SAMPLE_PLACEHOLDER_HEIGHT))
    return
  }
  CachedCardPreview(
    cacheKey = CardStyleSampleKey(style, dark, cardStyle.id),
    profile = androidXExperimentalWrap,
    modifier = modifier.fillMaxWidth(),
    card = SAMPLE_CARD,
    snapshot = SAMPLE_SNAPSHOT,
    template = template,
  ) {
    ProvideCardRegistry(registry) {
      ProvideCardStyle(cardStyle, haTheme, dark) {
        RenderChild(SAMPLE_CARD, SAMPLE_SNAPSHOT, RemoteModifier.rcFillMaxWidth())
      }
    }
  }
}

/** A loaded sample's template; null [playback] when the tile has none and captures directly. */
private class SampleTemplate(val playback: CardTemplatePlayback?)

private val SAMPLE_PLACEHOLDER_HEIGHT = 64.dp

private data class CardStyleSampleKey(
  val style: ThemeStyle,
  val dark: Boolean,
  val cardStyle: String,
)

private const val SAMPLE_ENTITY = "light.living_room"

private val SAMPLE_CARD =
  CardConfig(
    type = "tile",
    raw =
      buildJsonObject {
        put("type", "tile")
        put("entity", SAMPLE_ENTITY)
      },
  )

private val SAMPLE_SNAPSHOT =
  HaSnapshot(
    states =
      mapOf(
        SAMPLE_ENTITY to
          EntityState(
            entityId = SAMPLE_ENTITY,
            state = "on",
            attributes =
              buildJsonObject {
                put("friendly_name", "Living room")
                put("icon", "mdi:sofa")
                put("brightness", 178)
                put("color_mode", "brightness")
              },
          )
      )
  )
