package ee.schimke.terrazzo.wear.widget

import androidx.compose.runtime.CompositionLocalProvider
import androidx.wear.compose.remote.material3.RemoteColorScheme
import ee.schimke.ha.cards.CardDesignDocument
import ee.schimke.ha.cards.LocalCardDesignInput
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.rc.UNAVAILABLE_DESIGN_PROPERTY
import ee.schimke.ha.rc.cards.TileCardConverter
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.framedDesignTemplate
import ee.schimke.terrazzo.wear.ui.terrazzoWearColorScheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Wear widget is a rewrite of the card's design, not a source of its own
 * (server-owned-card-designs.md, "One source, every surface"): a slot card's container-neutral
 * template captured from the card's `.uid` is the one its converter gives, byte for byte, for every
 * templated tile shape, available or not, in every watch palette and card style.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WearCardDesignParityTest {

  private val context = RuntimeEnvironment.getApplication()
  private val registry = defaultRegistry()
  private val converter = TileCardConverter()

  private val design =
    CardDesignDocument.parse(
      File(requireNotNull(System.getProperty("cardDesignsDir")), "tile.uid").readText()
    )

  @Test
  fun tileDesignIsTheWearTemplateForEveryShapeAndStyle() {
    val inputs =
      TileCardConverter.TEMPLATE_DOMAINS.flatMap { domain ->
        listOf(false, true).mapNotNull { unavailable ->
          converter.framedDesignTemplate(
            DesignProperties.build {
              string(TileCardConverter.DOMAIN, domain)
              if (unavailable) string(UNAVAILABLE_DESIGN_PROPERTY, "1")
            }
          )
        }
      }
    val differing = buildList {
      for (input in inputs) for (style in ThemeStyle.entries) for (cardStyle in HaUberTheme.all) {
        val colors = RemoteColorScheme(terrazzoWearColorScheme(style))
        val fromConverter = runBlocking {
          WearWidgetTemplate.capture(context) {
            SlotContent(registry, colors, input.card, input.snapshot, 0, true, cardStyle)
          }
        }
        val fromDesign = runBlocking {
          WearWidgetTemplate.capture(context) {
            SlotContent(registry, colors, input.card, input.snapshot, 0, true, cardStyle) {
              CompositionLocalProvider(LocalCardDesignInput provides input) { design.Render(it) }
            }
          }
        }
        if (!fromConverter.contentEquals(fromDesign)) {
          add("${input.card.raw} ${input.snapshot.states.values} ${style.name} ${cardStyle.id}")
        }
      }
    }
    println("tile.uid: ${inputs.size * ThemeStyle.entries.size * HaUberTheme.all.size} Wear cases")
    assertEquals(emptyList(), differing)
  }
}
