package ee.schimke.ha.rc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.haThemeFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A linked markdown badge (`[![ci](badge.svg)](actions)`, as the GitHub dashboard's READMEs carry)
 * is a button a screen reader can name, with a full 48dp touch target.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownBadgeTargetTest {

  @get:Rule val compose = createComposeRule()

  private val card =
    CardConfig(
      type = "markdown",
      raw =
        buildJsonObject {
          put("type", "markdown")
          put(
            "content",
            "[![ci](https://example.com/ci/badge.svg)](https://example.com/actions) " +
              "[![License](https://example.com/license.svg)](https://example.com/license)",
          )
        },
    )

  @Test
  fun aLinkedBadgeIsANamedFullSizeButton() {
    val snapshot = HaSnapshot()
    val registry = defaultRegistry()
    val theme = haThemeFor(ThemeStyle.TerrazzoHome, false)
    compose.setContent {
      Box(Modifier.background(Color.White).width(360.dp)) {
        CachedCardPreview(
          cacheKey = "markdown-badges",
          profile = androidXExperimentalWrap,
          card = card,
          snapshot = snapshot,
        ) {
          ProvideCardRegistry(registry) {
            ProvideHaTheme(theme) { RenderChild(card, snapshot, RemoteModifier.fillMaxWidth()) }
          }
        }
      }
    }
    compose.waitForIdle()

    for (label in listOf("ci", "License")) {
      val buttons =
        compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().filter {
          it.config.getOrNull(SemanticsActions.OnClick) != null
        }
      assertEquals(1, buttons.size, "one tappable node labelled \"$label\"")
      val heightDp = buttons.single().size.height / buttons.single().layoutInfo.density.density
      assertTrue(heightDp >= 48f, "\"$label\" is a 48dp touch target, was ${heightDp}dp")
      assertTrue(
        buttons.single().config.getOrNull(SemanticsProperties.ContentDescription) != null,
        "the button itself carries the label",
      )
    }
  }
}
