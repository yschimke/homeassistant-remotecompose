package ee.schimke.terrazzo.wear.widget

import ee.schimke.ha.cards.CardDesignDocument
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.UNAVAILABLE_DESIGN_PROPERTY
import ee.schimke.ha.rc.cards.ButtonCardConverter
import ee.schimke.ha.rc.cards.CalendarCardConverter
import ee.schimke.ha.rc.cards.ClockCardConverter
import ee.schimke.ha.rc.cards.EntityCardConverter
import ee.schimke.ha.rc.cards.HeadingCardConverter
import ee.schimke.ha.rc.cards.HumidifierCardConverter
import ee.schimke.ha.rc.cards.LightCardConverter
import ee.schimke.ha.rc.cards.LogbookCardConverter
import ee.schimke.ha.rc.cards.SensorCardConverter
import ee.schimke.ha.rc.cards.ThermostatCardConverter
import ee.schimke.ha.rc.cards.TileCardConverter
import ee.schimke.ha.rc.cards.TodoListCardConverter
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

  private fun designOf(cardType: String) =
    CardDesignDocument.parse(
      File(requireNotNull(System.getProperty("cardDesignsDir")), "$cardType.uid").readText()
    )

  private val design = designOf("tile")

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

  @Test
  fun buttonDesignIsTheWearTemplateForEveryShapeAndStyle() {
    val button = ButtonCardConverter()
    val buttonDesign = designOf("button")
    // Every templated domain, with and without its name, available or not, off or on.
    val inputs =
      TileCardConverter.TEMPLATE_DOMAINS.flatMap { domain ->
        listOf("0", "1").flatMap { showName ->
          listOf(false, true).flatMap { unavailable ->
            listOf(false, true).mapNotNull { on ->
              if (on && domain == "sensor") return@mapNotNull null
              button.wearDesignTemplate(
                DesignProperties.build {
                  string(TileCardConverter.DOMAIN, domain)
                  string("show_name", showName)
                  if (unavailable) string(UNAVAILABLE_DESIGN_PROPERTY, "1")
                  if (on) string(WEAR_ON_PROPERTY, "1")
                }
              )
            }
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
              buttonDesign.RenderCard(input.card, input.snapshot, it)
            }
          }
        }
        if (!fromConverter.contentEquals(fromDesign)) {
          add("${input.card.raw} ${input.snapshot.states.values} ${style.name} ${cardStyle.id}")
        }
      }
    }
    println(
      "button.uid: ${inputs.size * ThemeStyle.entries.size * HaUberTheme.all.size} Wear cases"
    )
    assertEquals(emptyList(), differing)
  }

  @Test
  fun entityDesignIsTheWearTemplateForEveryShapeAndStyle() {
    val entity = EntityCardConverter()
    val entityDesign = designOf("entity")
    // Every templated domain, available or not, off or on.
    val inputs =
      TileCardConverter.TEMPLATE_DOMAINS.flatMap { domain ->
        listOf(false, true).flatMap { unavailable ->
          listOf(false, true).mapNotNull { on ->
            if (on && domain == "sensor") return@mapNotNull null
            entity.wearDesignTemplate(
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
              entityDesign.RenderCard(input.card, input.snapshot, it)
            }
          }
        }
        if (!fromConverter.contentEquals(fromDesign)) {
          add("${input.card.raw} ${input.snapshot.states.values} ${style.name} ${cardStyle.id}")
        }
      }
    }
    println(
      "entity.uid: ${inputs.size * ThemeStyle.entries.size * HaUberTheme.all.size} Wear cases"
    )
    assertEquals(emptyList(), differing)
  }

  @Test
  fun sensorDesignIsTheWearTemplateForEveryShapeAndStyle() {
    val sensor = SensorCardConverter()
    val sensorDesign = designOf("sensor")
    // A sensor with and without a history line, available or not.
    val inputs =
      listOf("0", "1").flatMap { hasData ->
        listOf(false, true).mapNotNull { unavailable ->
          sensor.wearDesignTemplate(
            DesignProperties.build {
              string(TileCardConverter.DOMAIN, "sensor")
              string("has_data", hasData)
              if (unavailable) string(UNAVAILABLE_DESIGN_PROPERTY, "1")
            }
          )
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
              sensorDesign.RenderCard(input.card, input.snapshot, it)
            }
          }
        }
        if (!fromConverter.contentEquals(fromDesign)) {
          add("${input.card.raw} ${input.snapshot.states.values} ${style.name} ${cardStyle.id}")
        }
      }
    }
    println(
      "sensor.uid: ${inputs.size * ThemeStyle.entries.size * HaUberTheme.all.size} Wear cases"
    )
    assertEquals(emptyList(), differing)
  }

  @Test
  fun headingDesignIsTheWearTemplateForEveryShapeAndStyle() {
    val heading = HeadingCardConverter()
    val headingDesign = designOf("heading")
    // Both heading styles.
    val inputs =
      listOf("title", "subtitle").mapNotNull { style ->
        heading.wearDesignTemplate(DesignProperties.build { string("heading_style", style) })
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
              headingDesign.RenderCard(input.card, input.snapshot, it)
            }
          }
        }
        if (!fromConverter.contentEquals(fromDesign)) {
          add("${input.card.raw} ${input.snapshot.states.values} ${style.name} ${cardStyle.id}")
        }
      }
    }
    println(
      "heading.uid: ${inputs.size * ThemeStyle.entries.size * HaUberTheme.all.size} Wear cases"
    )
    assertEquals(emptyList(), differing)
  }

  @Test
  fun clockDesignIsTheWearTemplateForEveryShapeAndStyle() {
    val clock = ClockCardConverter()
    val clockDesign = designOf("clock")
    // Each line on and off against a plain 24-hour clock, and a 12-hour clock with every line.
    val plain = mapOf("has_title" to 0, "seconds" to 0, "h24" to 1, "large" to 0, "zoned" to 0)
    val inputs =
      (listOf(plain + ("date" to 0)) +
          listOf("has_title", "seconds", "large", "zoned", "date").map {
            plain + ("date" to 0) + (it to 1)
          } +
          listOf(
            mapOf("has_title" to 1, "seconds" to 1, "h24" to 0, "large" to 1) +
              ("zoned" to 1) +
              ("date" to 1)
          ))
        .mapNotNull { values ->
          clock.wearDesignTemplate(
            DesignProperties.build { values.forEach { (name, value) -> enum(name, value) } }
          )
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
              clockDesign.RenderCard(input.card, input.snapshot, it)
            }
          }
        }
        if (!fromConverter.contentEquals(fromDesign)) {
          add("${input.card.raw} ${style.name} ${cardStyle.id}")
        }
      }
    }
    println("clock.uid: ${inputs.size * ThemeStyle.entries.size * HaUberTheme.all.size} Wear cases")
    assertEquals(7, inputs.size)
    assertEquals(emptyList(), differing)
  }

  @Test
  fun arcDialDesignsAreTheWearTemplateForEveryShapeAndStyle() {
    val snapshot =
      HaSnapshot(
        states =
          mapOf(
            "climate.living_room" to
              EntityState(
                "climate.living_room",
                "heat",
                attributes(
                  """{"current_temperature":20.5,"temperature":21,"hvac_action":"heating"}"""
                ),
              ),
            "climate.office" to EntityState("climate.office", "off"),
            "humidifier.bedroom" to
              EntityState(
                "humidifier.bedroom",
                "on",
                attributes("""{"current_humidity":45,"humidity":55,"action":"humidifying"}"""),
              ),
            "light.kitchen" to
              EntityState("light.kitchen", "on", attributes("""{"brightness":200}""")),
            "light.hall" to EntityState("light.hall", "off"),
          )
      )
    assertWearParity(
      "arc dial designs",
      listOf(ThermostatCardConverter(), HumidifierCardConverter(), LightCardConverter()),
      snapshot,
      listOf(
        """{"type":"thermostat","entity":"climate.living_room"}""",
        """{"type":"thermostat","entity":"climate.office","name":"Office"}""",
        """{"type":"humidifier","entity":"humidifier.bedroom"}""",
        """{"type":"light","entity":"light.kitchen"}""",
        """{"type":"light","entity":"light.hall"}""",
      ),
    )
  }

  @Test
  fun listDesignsAreTheWearTemplateForEveryShapeAndStyle() {
    val snapshot =
      HaSnapshot(
        states =
          mapOf(
            "light.kitchen" to EntityState("light.kitchen", "on"),
            "switch.fan" to EntityState("switch.fan", "off"),
            "calendar.home" to
              EntityState(
                "calendar.home",
                "on",
                attributes(
                  """{"message":"Bins out","start_time":"2026-10-24 07:00:00","friendly_name":"Home"}"""
                ),
              ),
            "todo.shopping" to
              EntityState(
                "todo.shopping",
                "2",
                attributes(
                  """{"items":[{"summary":"Milk","status":"needs_action"},""" +
                    """{"summary":"Eggs","status":"needs_action"},""" +
                    """{"summary":"Bread","status":"completed"}]}"""
                ),
              ),
          )
      )
    assertWearParity(
      "list designs",
      listOf(LogbookCardConverter(), CalendarCardConverter(), TodoListCardConverter()),
      snapshot,
      listOf(
        """{"type":"logbook","entities":["light.kitchen","switch.fan"]}""",
        """{"type":"logbook","title":"Activity","entities":["light.kitchen"]}""",
        """{"type":"calendar","entities":["calendar.home"]}""",
        """{"type":"todo-list","entity":"todo.shopping"}""",
        """{"type":"todo-list","entity":"todo.shopping","title":"Shopping"}""",
      ),
    )
  }

  private fun attributes(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

  /**
   * Every one of [cards] (JSON configs) that has a Wear template captures the same template from
   * its converter as from its card's `.uid`, in every watch palette and card style; and each of
   * [converters] has at least one.
   */
  private fun assertWearParity(
    label: String,
    converters: List<CardConverter>,
    snapshot: HaSnapshot,
    cards: List<String>,
  ) {
    val byType = converters.associateBy { it.cardType }
    val inputs = cards.mapNotNull { json ->
      val raw = Json.parseToJsonElement(json) as JsonObject
      val converter = byType.getValue(raw.getValue("type").toString().trim('"'))
      val card = CardConfig(converter.cardType, raw)
      // A card whose shape has no template draws direct on a watch, with nothing to compare.
      val properties = converter.wearDesignProperties(card, snapshot) ?: return@mapNotNull null
      converter.wearDesignTemplate(properties)?.let { converter.cardType to it }
    }
    assertEquals(byType.keys, inputs.map { it.first }.toSet())
    val differing = buildList {
      for ((cardType, input) in inputs) for (style in ThemeStyle.entries) {
        for (cardStyle in HaUberTheme.all) {
          val colors = wearWidgetColors(style)
          val fromConverter = runBlocking {
            WearWidgetTemplate.capture(context) {
              SlotContent(registry, colors, input.card, input.snapshot, true, cardStyle)
            }
          }
          val fromDesign = runBlocking {
            WearWidgetTemplate.capture(context) {
              SlotContent(registry, colors, input.card, input.snapshot, true, cardStyle) {
                designOf(cardType).RenderCard(input.card, input.snapshot, it)
              }
            }
          }
          if (!fromConverter.contentEquals(fromDesign)) {
            add("${input.card.raw} ${style.name} ${cardStyle.id}")
          }
        }
      }
    }
    println("$label: ${inputs.size * ThemeStyle.entries.size * HaUberTheme.all.size} Wear cases")
    assertEquals(emptyList(), differing)
  }
}
