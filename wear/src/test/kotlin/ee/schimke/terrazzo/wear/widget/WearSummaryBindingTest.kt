package ee.schimke.terrazzo.wear.widget

import androidx.glance.wear.core.ContainerInfo
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.core.WidgetInstanceId
import androidx.wear.compose.remote.material3.RemoteColorScheme
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.converterFor
import ee.schimke.ha.rc.framedDesignProperties
import ee.schimke.ha.rc.framedDesignTemplate
import ee.schimke.terrazzo.wear.ui.terrazzoWearColorScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A list card's Wear Small tier is one summary line, and the widget renders it from the card's
 * design. The line must be a binding the bake fills with the real card's text: as a literal, the
 * template's own placeholder list reached the watch instead of the user's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WearSummaryBindingTest {
  private val registry = defaultRegistry()
  private val colors = RemoteColorScheme(terrazzoWearColorScheme(ThemeStyle.entries.first()))

  private val small =
    WearWidgetParams(
      instanceId = WidgetInstanceId("widgets", 1),
      containerType = ContainerInfo.CONTAINER_TYPE_SMALL,
      widthDp = 200f,
      heightDp = 60f,
      verticalPaddingDp = 8f,
      horizontalPaddingDp = 8f,
      cornerRadiusDp = 26f,
    )

  private fun item(summary: String, status: String) = buildJsonObject {
    put("summary", summary)
    put("status", status)
  }

  private val todo =
    CardConfig(
      type = "todo-list",
      raw =
        buildJsonObject {
          put("type", "todo-list")
          put("entity", "todo.shopping")
          put("title", "Shopping")
        },
    )
  private val snapshot =
    HaSnapshot(
      states =
        mapOf(
          "todo.shopping" to
            EntityState(
              "todo.shopping",
              "2",
              JsonObject(
                mapOf(
                  "items" to
                    JsonArray(
                      listOf(
                        item("Milk", "needs_action"),
                        item("Bread", "needs_action"),
                        item("Eggs", "completed"),
                      )
                    )
                )
              ),
            )
        )
    )

  @Test
  fun theTodoSummaryLineIsABindingTheBakeFills() {
    val design = CardDesignTemplates.designCard(todo, snapshot, registry)
    val converter = requireNotNull(registry.converterFor(design.type))
    val input =
      requireNotNull(
        converter.framedDesignTemplate(
          requireNotNull(converter.framedDesignProperties(design, snapshot))
        )
      )
    val document = runBlocking {
      slotDocument(
          registry,
          colors,
          input.card,
          input.snapshot,
          template = true,
          cardStyle = HaUberTheme.Classic,
        )
        .captureRawContent(RuntimeEnvironment.getApplication(), small, false)
        .rcDocument
    }
    val text = String(document, Charsets.ISO_8859_1)
    assertTrue("card.summary" in text, "the summary line binds card.summary")
    assertTrue("card.title" in text, "the title binds card.title")

    val bindings = requireNotNull(converter.designBindings(todo, snapshot))
    assertEquals("2 left · Milk", bindings.strings["card.summary"])
    assertEquals("Shopping", bindings.strings["card.title"])
    assertFalse("Milk" in text, "the template must not bake the real list")
  }
}
