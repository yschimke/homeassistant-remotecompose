package ee.schimke.terrazzo.wear.widget

import ee.schimke.ha.cards.CardDesignDocument
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.rc.UNAVAILABLE_DESIGN_PROPERTY
import ee.schimke.ha.rc.cards.TileCardConverter
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
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
 * templated tile shape, available or not, off or on, in every watch palette and card style.
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
    // Built as SlotWidget builds a slot card's Wear template input: every templated tile domain,
    // available or not, off or (for the domains whose templates have one) on.
    val inputs =
      TileCardConverter.TEMPLATE_DOMAINS.flatMap { domain ->
        listOf(false, true).flatMap { unavailable ->
          listOf(false, true).mapNotNull { on ->
            // Only a domain whose on state is literally `on` gets an on template (SlotWidget).
            if (on && domain == "sensor") return@mapNotNull null
            converter.wearDesignTemplate(
              DesignProperties.build {
                string(TileCardConverter.DOMAIN, domain)
                if (unavailable) string(UNAVAILABLE_DESIGN_PROPERTY, "1")
                if (on) string(WEAR_ON_PROPERTY, "1")
              }
            )
          }
        }
      }
    val differing = buildList {
      for (input in inputs) for (style in ThemeStyle.entries) for (cardStyle in HaUberTheme.all) {
        val colors = wearWidgetColors(style)
        val fromConverter = runBlocking {
          WearWidgetTemplate.capture(context) {
            SlotContent(registry, colors, input.card, input.snapshot, true, cardStyle)
          }
        }
        val fromDesign = runBlocking {
          WearWidgetTemplate.capture(context) {
            SlotContent(registry, colors, input.card, input.snapshot, true, cardStyle) {
              design.RenderCard(input.card, input.snapshot, it)
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
