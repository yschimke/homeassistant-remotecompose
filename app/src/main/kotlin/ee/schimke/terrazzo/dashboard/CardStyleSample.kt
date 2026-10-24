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
 * A tile card drawn in [cardStyle], on the app's palette and dark mode, that names the style
 * ([name]) and describes it ([description]): each option of the card style picker in Settings, so a
 * style is judged by a real card rather than a label. Renders the way a dashboard slot does — the
 * local design library's template where the tile has one, else its converter — but from a frozen
 * sample, so it never changes and needs no session.
 */
@Composable
internal fun CardStyleSample(
  cardStyle: HaUberTheme,
  name: String,
  description: String,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val registry = remember { defaultRegistry().withEnhancedShutter() }
  val style = LocalThemeStyle.current
  val dark = LocalIsDarkTheme.current
  val haTheme = remember(style, dark, cardStyle) { cardStyle.cardPalette(style, dark) }
  val snapshot = remember(name, description) { sampleSnapshot(name, description) }
  // Plain CardDesigns: the local library only. The server's designs are fetched for the chosen
  // style alone, and reading them here would log the other styles as the server's gaps.
  val loadTemplate = {
    cardTemplateFor(
      context,
      SAMPLE_CARD,
      snapshot,
      CardDesigns(),
      style,
      dark,
      registry,
      cardStyle,
      // A sample, not one of the user's cards.
      recordUsage = false,
    )
  }
  // The library captures a template the first time a key is asked for. Expanding the picker asks
  // for every style at once, so the samples load off the UI thread (as widget previews capture)
  // and hold their height until then. Previews load in place, so a render shows the card.
  val inspection = LocalInspectionMode.current
  val inPlace =
    remember(style, dark, cardStyle, snapshot) {
      if (inspection) SampleTemplate(loadTemplate()) else null
    }
  val loaded by
    produceState(
      initialValue = inPlace,
      style,
      dark,
      cardStyle,
      snapshot,
    ) {
      if (value == null) value = SampleTemplate(withContext(Dispatchers.Default) { loadTemplate() })
    }
  val template = loaded?.playback
  if (loaded == null) {
    Box(modifier.fillMaxWidth().height(SAMPLE_PLACEHOLDER_HEIGHT))
    return
  }
  CachedCardPreview(
    cacheKey = CardStyleSampleKey(style, dark, cardStyle.id, name, description),
    profile = androidXExperimentalWrap,
    modifier = modifier.fillMaxWidth(),
    card = SAMPLE_CARD,
    snapshot = snapshot,
    template = template,
  ) {
    ProvideCardRegistry(registry) {
      ProvideCardStyle(cardStyle, haTheme, dark) {
        RenderChild(SAMPLE_CARD, snapshot, RemoteModifier.rcFillMaxWidth())
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
  val name: String,
  val description: String,
)

internal const val SAMPLE_ENTITY = "sensor.card_style"

internal val SAMPLE_CARD =
  CardConfig(
    type = "tile",
    raw =
      buildJsonObject {
        put("type", "tile")
        put("entity", SAMPLE_ENTITY)
      },
  )

/**
 * A text sensor whose state is the style's [name] and whose name is its [description]. The state
 * slot is the one every style draws, and draws large (an LCD's segments, an Expressive pill), so
 * the style's name always shows in full; the description fills the smaller name line where the
 * style has one. Plain entity state rather than `state_content`, which would take the tile off the
 * template path and capture it on the UI thread.
 */
internal fun sampleSnapshot(name: String, description: String) =
  HaSnapshot(
    states =
      mapOf(
        SAMPLE_ENTITY to
          EntityState(
            entityId = SAMPLE_ENTITY,
            state = name,
            attributes =
              buildJsonObject {
                put("friendly_name", description)
                put("icon", "mdi:palette")
              },
          )
      )
  )
