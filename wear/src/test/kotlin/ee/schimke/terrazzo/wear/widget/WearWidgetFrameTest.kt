package ee.schimke.terrazzo.wear.widget

import androidx.glance.wear.core.ContainerInfo
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.core.WidgetInstanceId
import ee.schimke.composeai.rcplayer.edit.namedVariables
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.converterFor
import ee.schimke.terrazzo.wear.ui.WearCardLook
import ee.schimke.terrazzo.wear.ui.wearAppTemplate
import ee.schimke.terrazzo.wear.ui.wearTemplateShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The Wear app draws each card in the frame its widget gets on this watch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WearWidgetFrameTest {
  private val context = RuntimeEnvironment.getApplication()
  private val registry = defaultRegistry()

  private fun card(json: String) = Json.decodeFromString(CardConfig.serializer(), json)

  private val snapshot =
    HaSnapshot(
      states =
        listOf("sensor.living_room" to "21.5", "light.kitchen" to "on", "switch.pump" to "off")
          .associate { (id, state) -> id to EntityState(id, state) }
    )

  @After
  fun clearFrames() {
    context.getSharedPreferences("wear_widget_frames", 0).edit().clear().commit()
  }

  @Test
  fun aOneRowCardTakesTheSmallContainerAndAListTheLarge() {
    val tile = card("""{"type":"tile","entity":"light.kitchen"}""")
    assertEquals(DesignKey.WEAR_SMALL, wearAppContainer(tile, snapshot, registry))
    val entities =
      card(
        """{"type":"entities","entities":["sensor.living_room","light.kitchen","switch.pump"]}"""
      )
    assertEquals(DesignKey.WEAR_LARGE, wearAppContainer(entities, snapshot, registry))
    // A card whose data fills only the small container never takes the large one.
    val markdown = card("""{"type":"markdown","content":"# A long note\n\nwith\n\nlines"}""")
    assertEquals(DesignKey.WEAR_SMALL, wearAppContainer(markdown, snapshot, registry))
  }

  /**
   * A light that is on gets a template captured with it on, so the tiers that resolve their icon
   * when captured show the lit bulb. Off keeps the plain key; a thermostat's `heat` is active but
   * never captured as a literal `"on"`.
   */
  @Test
  fun aLightThatIsOnPlaysATemplateCapturedOn() {
    val tile = assertNotNull(registry.converterFor("tile"))
    val lamp = card("""{"type":"tile","entity":"light.kitchen"}""")
    fun properties(state: String) =
      assertNotNull(
        tile.wearDesignProperties(
          lamp,
          HaSnapshot(states = mapOf("light.kitchen" to EntityState("light.kitchen", state))),
        )
      )
    assertEquals("1", properties("on")[WEAR_ON_PROPERTY])
    assertEquals(null, properties("off")[WEAR_ON_PROPERTY])
    val input = assertNotNull(tile.wearDesignTemplate(properties("on")))
    assertEquals(listOf("on"), input.snapshot.states.values.map { it.state })

    val heating = HaSnapshot(states = mapOf("climate.hall" to EntityState("climate.hall", "heat")))
    registry
      .converterFor("thermostat")
      ?.wearDesignProperties(card("""{"type":"thermostat","entity":"climate.hall"}"""), heating)
      ?.let { assertEquals(null, it[WEAR_ON_PROPERTY]) }
  }

  /** A switch turning on keys a new template for the app, though nothing else about it changes. */
  @Test
  fun aToggleTurningOnChangesTheAppsTemplateShape() {
    val tile = card("""{"type":"tile","entity":"switch.pump"}""")
    fun shape(state: String) =
      wearTemplateShape(
        tile,
        HaSnapshot(states = mapOf("switch.pump" to EntityState("switch.pump", state))),
        registry,
      )
    assertNotNull(shape("off"))
    assertNotEquals(shape("off"), shape("on"))
    assertEquals(shape("off"), shape("off"))
  }

  @Test
  fun theFrameIsTheOneThisWatchsWidgetHostLastGave() {
    assertEquals(
      WearWidgetFrame.DEFAULT_LARGE,
      WearWidgetFrame.forContainer(context, DesignKey.WEAR_LARGE),
    )
    // A Galaxy-shaped host: a narrower, taller large container, fully rounded.
    WearWidgetFrame.remember(
      context,
      WearWidgetParams(
        instanceId = WidgetInstanceId("widgets", 3),
        containerType = ContainerInfo.CONTAINER_TYPE_LARGE,
        widthDp = 160f,
        heightDp = 136f,
        verticalPaddingDp = 16f,
        horizontalPaddingDp = 35f,
        cornerRadiusDp = 999f,
      ),
    )
    assertEquals(
      WearWidgetFrame(DesignKey.WEAR_LARGE, 160f, 136f, 35f, 16f, 999f),
      WearWidgetFrame.forContainer(context, DesignKey.WEAR_LARGE),
    )
    // The small container keeps its own until a small widget reports one.
    assertEquals(
      WearWidgetFrame.DEFAULT_SMALL,
      WearWidgetFrame.forContainer(context, DesignKey.WEAR_SMALL),
    )
  }

  @Test
  fun theAppsCardIsTheWidgetTemplateWithTheFrameFilledInAndItsBindingsKept() {
    val tile = card("""{"type":"tile","entity":"light.kitchen"}""")
    val design = CardDesignTemplates.designCard(tile, snapshot, registry)
    val document = runBlocking {
      wearAppTemplate(
        context,
        design,
        snapshot,
        WearCardLook(ThemeStyle.TerrazzoHome, "classic"),
        ee.schimke.ha.rc.components.HaUberTheme.Classic,
        WearWidgetFrame.DEFAULT_SMALL,
        tint = null,
        registry,
      )
    }
    val names =
      RcDocumentCodec.decode(assertNotNull(document), RcWireLimits()).namedVariables.map {
        it.name.removePrefix("USER:")
      }
    // The container's values are filled in, so nothing on the watch has to supply them…
    for (frame in
      listOf(
        WearWidgetTemplate.HORIZONTAL_PADDING,
        WearWidgetTemplate.VERTICAL_PADDING,
        WearWidgetTemplate.CORNER_RADIUS,
        WearWidgetTemplate.CONTAINER_COLOR,
      )) {
      assertFalse(frame in names, "$frame is filled in: $names")
    }
    // …and the card's own values stay bindings, which the app pushes live.
    assertNotNull(names.firstOrNull { it.endsWith(".state") }, "a state binding: $names")
  }
}
