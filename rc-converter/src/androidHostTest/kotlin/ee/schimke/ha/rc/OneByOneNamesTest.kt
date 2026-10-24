package ee.schimke.ha.rc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.WidgetActionRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A 1×1 launcher widget keeps its name under a generic icon and drops it under an evocative one
 * (`docs/architecture/card-information-hierarchy.md`, the icon exception; [HaIconIdentity]). Each
 * card is captured the way the launcher captures it, for a 72 × 84 dp cell, and played: a drawn
 * name is a text node, and the icon then carries no second label; an icon standing alone carries
 * the name as its description instead, so the widget is still announced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OneByOneNamesTest {

  @get:Rule val compose = createComposeRule()

  private val registry = defaultRegistry()

  private fun entity(id: String, state: String, name: String, vararg attrs: Pair<String, String>) =
    EntityState(
      id,
      state,
      JsonObject(
        mapOf("friendly_name" to JsonPrimitive(name)) +
          attrs.associate { (key, value) -> key to JsonPrimitive(value) }
      ),
    )

  private val snapshot =
    HaSnapshot(
      states =
        listOf(
            entity("light.kitchen", "on", "Kitchen", "brightness" to "200"),
            entity("lock.front_door", "locked", "Front door"),
            entity(
              "sensor.outside",
              "8.2",
              "Outside",
              "device_class" to "temperature",
              "unit_of_measurement" to "°C",
            ),
            entity("sensor.power", "312", "Power", "unit_of_measurement" to "W"),
            entity(
              "climate.hall",
              "heat",
              "Hall",
              "current_temperature" to "21.4",
              "temperature" to "22",
            ),
          )
          .associateBy { it.entityId }
    )

  private fun card(type: String, entity: String, icon: String? = null) =
    CardConfig(
      type = type,
      raw =
        buildJsonObject {
          put("type", type)
          put("entity", entity)
          icon?.let { put("icon", it) }
        },
    )

  private fun render1x1(card: CardConfig) {
    val actions =
      WidgetActionRegistry(snapshot.states.mapValues { (_, e) -> formatState(e, snapshot) })
    compose.setContent {
      Box(Modifier.background(Color.Black).size(72.dp, 84.dp)) {
        CachedCardPreview(
          cacheKey = "one-by-one-${card.raw}",
          profile = androidXExperimentalWrap,
          modifier = Modifier.fillMaxSize(),
          card = card,
          snapshot = snapshot,
        ) {
          LauncherWidgetContent(
            registry,
            androidXExperimentalWrap,
            dark = true,
            widgetActions = actions,
            card = card,
            snapshot = snapshot,
            captureSizeDp = WidgetCaptureSizeDp(72, 84),
          )
        }
      }
    }
    compose.waitForIdle()
  }

  private fun drawn(name: String) = compose.onAllNodesWithText(name).fetchSemanticsNodes().size

  private fun described(name: String) =
    compose.onAllNodesWithContentDescription(name).fetchSemanticsNodes().size

  private fun assertNameDrawn(card: CardConfig, name: String) {
    render1x1(card)
    assertEquals(1, drawn(name), "${card.type} · ${card.raw["entity"]}: \"$name\" drawn at 1×1")
    assertEquals(0, described(name), "${card.type}: the icon is decorative beside the name")
  }

  private fun assertNameDropped(card: CardConfig, name: String) {
    render1x1(card)
    assertEquals(0, drawn(name), "${card.type} · ${card.raw["entity"]}: the icon stands alone")
    assertEquals(1, described(name), "${card.type}: the lone icon is labelled \"$name\"")
  }

  @Test
  fun aButtonKeepsTheNameUnderAGenericBulb() =
    assertNameDrawn(card("button", "light.kitchen"), "Kitchen")

  @Test
  fun aButtonDropsTheNameUnderAnEvocativePadlock() =
    assertNameDropped(card("button", "lock.front_door"), "Front door")

  @Test
  fun aButtonWithAConfiguredIconDropsTheName() =
    assertNameDropped(card("button", "light.kitchen", icon = "mdi:ceiling-light"), "Kitchen")

  @Test
  fun aTileKeepsTheNameUnderAGenericBulb() =
    assertNameDrawn(card("tile", "light.kitchen"), "Kitchen")

  @Test
  fun aTileDropsTheNameUnderAnEvocativePadlock() =
    assertNameDropped(card("tile", "lock.front_door"), "Front door")

  @Test
  fun anEntityCardKeepsTheNameUnderAGenericBulb() =
    assertNameDrawn(card("entity", "light.kitchen"), "Kitchen")

  @Test
  fun anEntityCardDropsTheNameUnderAnEvocativePadlock() =
    assertNameDropped(card("entity", "lock.front_door"), "Front door")

  @Test
  fun aSensorKeepsTheNameUnderItsIcon() = assertNameDrawn(card("sensor", "sensor.power"), "Power")

  /** The thermometer is the same in every room: the measurement chip keeps the name too. */
  @Test
  fun aTemperatureSensorKeepsTheNameUnderItsThermometer() {
    render1x1(card("sensor", "sensor.outside"))
    assertEquals(1, drawn("Outside"), "the thermometer chip draws the name")
  }

  @Test
  fun aStatisticKeepsTheNameUnderItsIcon() {
    render1x1(card("statistic", "sensor.power"))
    assertEquals(1, drawn("Power"), "the statistic chip draws the name")
  }

  /** A one-column dial has no room beside its dial: the value goes under it, then the name. */
  @Test
  fun aLightDialKeepsItsValueAndNameUnderTheDial() {
    render1x1(card("light", "light.kitchen"))
    assertEquals(1, drawn("Kitchen"), "the generic bulb's dial draws the name")
    assertEquals(1, drawn("78%"), "the light's brightness is drawn under the dial")
  }

  @Test
  fun aThermostatDialKeepsItsValueAndDropsTheName() {
    render1x1(card("thermostat", "climate.hall"))
    assertEquals(0, drawn("Hall"), "a climate dial stands for its entity on its own")
    assertTrue(
      compose.onAllNodesWithText("21.4", substring = true).fetchSemanticsNodes().isNotEmpty(),
      "the temperature is drawn under the dial",
    )
  }
}
