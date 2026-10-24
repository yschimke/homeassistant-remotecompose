package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth as rcFillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ee.schimke.ha.rc.BreakpointAxis
import ee.schimke.ha.rc.CachedCardPreview
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.ProvideCardSizeMode
import ee.schimke.ha.rc.ProvideFixedCanvasAxis
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.androidXExperimentalWrap
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withEnhancedShutter
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ProvideCardChrome
import ee.schimke.ha.rc.components.ProvideCardStyle
import ee.schimke.ha.rc.components.ProvideFlowLayoutSupport
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.cardPalette
import ee.schimke.terrazzo.ui.LocalThemeStyle

/**
 * [cardStyle]'s sample card ([CardStyleSample]'s) as a Wear widget would show it, in approximate
 * watch screens: a round one and a square one, each with the Small and Large widget containers. The
 * card is drawn as the watch draws a widget (Fixed size, no card chrome of its own, tiers chosen by
 * the container's height, dark), inside the container a watch's widget host draws: Glance Wear's
 * preview sizes for a round watch (200×60 and 200×112 dp, 8 dp padding, 26 dp corners), on its
 * default surface. An approximation: the watch keeps its own palette and card style, and each
 * watch's host has its own container sizes.
 */
@Composable
internal fun CardStyleWearPanes(
  cardStyle: HaUberTheme,
  name: String,
  description: String,
  modifier: Modifier = Modifier,
) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    for (screen in WearScreen.entries) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (container in WearPane.entries) {
          WatchScreen(screen) { WearPaneCard(cardStyle, name, description, container) }
        }
      }
    }
  }
}

/** The Wear widget containers, at Glance Wear's preview sizes for a round watch. */
internal enum class WearPane(val widthDp: Dp, val heightDp: Dp) {
  Small(200.dp, 60.dp),
  Large(200.dp, 112.dp),
}

/** Approximate watch screens: a 1.4" round one and a square one with rounded corners. */
internal enum class WearScreen(val size: Dp, val shape: Shape) {
  Round(227.dp, CircleShape),
  Square(210.dp, RoundedCornerShape(44.dp)),
}

/** A watch screen of [screen]'s shape, drawn at [SCALE] of its size, with [content] centred. */
@Composable
private fun WatchScreen(screen: WearScreen, content: @Composable () -> Unit) {
  Box(Modifier.size(screen.size * SCALE), contentAlignment = Alignment.Center) {
    Box(
      Modifier.requiredSize(screen.size)
        .graphicsLayer {
          scaleX = SCALE
          scaleY = SCALE
        }
        .clip(screen.shape)
        .background(Color.Black),
      contentAlignment = Alignment.Center,
    ) {
      content()
    }
  }
}

/** The sample card in one Wear widget container. */
@Composable
private fun WearPaneCard(
  cardStyle: HaUberTheme,
  name: String,
  description: String,
  pane: WearPane,
) {
  val registry = remember { defaultRegistry().withEnhancedShutter() }
  val style = LocalThemeStyle.current
  val snapshot = remember(name, description) { sampleSnapshot(name, description) }
  val haTheme = remember(style, cardStyle) { cardStyle.cardPalette(style, darkTheme = true) }
  Box(
    Modifier.size(pane.widthDp, pane.heightDp)
      .clip(RoundedCornerShape(CONTAINER_CORNER))
      .background(WEAR_SURFACE)
      .padding(CONTAINER_PADDING)
  ) {
    CachedCardPreview(
      cacheKey = WearPaneKey(style, cardStyle.id, name, description, pane),
      profile = androidXExperimentalWrap,
      modifier =
        Modifier.size(
          pane.widthDp - CONTAINER_PADDING * 2,
          pane.heightDp - CONTAINER_PADDING * 2,
        ),
      card = SAMPLE_CARD,
      snapshot = snapshot,
    ) {
      ProvideCardRegistry(registry) {
        ProvideCardStyle(cardStyle, haTheme, darkTheme = true) {
          ProvideCardSizeMode(CardSizeMode.Fixed) {
            ProvideCardChrome(enabled = false) {
              ProvideFixedCanvasAxis(BreakpointAxis.Height) {
                ProvideFlowLayoutSupport(enabled = false, compactEntities = true) {
                  RenderChild(SAMPLE_CARD, snapshot, RemoteModifier.rcFillMaxWidth())
                }
              }
            }
          }
        }
      }
    }
  }
}

private data class WearPaneKey(
  val style: ThemeStyle,
  val cardStyle: String,
  val name: String,
  val description: String,
  val pane: WearPane,
)

/** How much smaller than life the watch screens are drawn, so two fit beside each other. */
private const val SCALE = 0.62f

private val CONTAINER_CORNER = 26.dp
private val CONTAINER_PADDING = 8.dp

/** The container colour a Wear widget host fills for an untinted widget. */
private val WEAR_SURFACE = Color(39, 36, 48)
