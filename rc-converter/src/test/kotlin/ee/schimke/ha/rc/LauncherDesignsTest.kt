package ee.schimke.ha.rc

import android.graphics.Bitmap
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.Operation
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.rcplayer.edit.bitmapUrls
import ee.schimke.composeai.rcplayer.edit.namedVariables
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.WidgetActionRegistry
import ee.schimke.ha.rc.components.WidgetOpenApp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
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
 * Launcher widgets from card designs: a launcher can't receive bindings, so the template is baked
 * with the card's values (`rc-player-edit`). The baked widget must hold no slot bindings, tap the
 * card's real entity, and draw what a direct widget capture draws.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherDesignsTest {

  @Test
  fun android15PlatformDoesNotSupportLayoutWidgetDocuments() {
    val context = org.robolectric.RuntimeEnvironment.getApplication()
    val document = bake(card("tile", "light.kitchen"), snapshotOf(entity("light.kitchen", "on")))
    val views = RemoteViews(RemoteViews.DrawInstructions.Builder(listOf(document.bytes)).build())
    // Android 15 has DrawInstructions but only its original canvas operations, without
    // layout widgets or the V7 header. The phone host must require Android 16 for widgets.
    assertFailsWith<RuntimeException> { views.apply(context, FrameLayout(context)) }
  }

  @Test
  fun bakedWidgetKeepsAndroid16CompatibleHeader() {
    val baked = bake(card("tile", "light.kitchen"), snapshotOf(entity("light.kitchen", "on")))
    val document =
      CoreDocument().apply {
        initFromBuffer(
          RemoteComposeBuffer.fromInputStream(platformWidgetDocument(baked.bytes).inputStream())
        )
      }
    assertTrue(document.canBeDisplayed(1, 0, 0L))
  }

  @Test
  fun adaptiveWidgetDefinesTextResourcesBeforePlatformLayout() {
    val baked = bake(card("tile", "light.kitchen"), snapshotOf(entity("light.kitchen", "on")))
    val operations = arrayListOf<Operation>()
    RemoteComposeBuffer.fromInputStream(platformWidgetDocument(baked.bytes).inputStream())
      .inflateFromBuffer(operations)
    val firstLayout = operations.indexOfFirst { it.javaClass.simpleName == "RootLayoutComponent" }
    assertTrue(firstLayout > 0)
    val textOperations =
      operations.withIndex().filter {
        it.value.javaClass.simpleName in setOf("TextData", "TextFromFloat", "TextMerge")
      }
    assertTrue(textOperations.isNotEmpty())
    assertTrue(
      operations.drop(firstLayout).any {
        it is androidx.compose.remote.core.operations.FloatExpression
      },
      "Canvas expressions must stay local rather than evaluate unmeasured inactive dimensions",
    )
    assertTrue(
      textOperations.all { it.index < firstLayout },
      "Android 16 must load text expressions before inflating adaptive layouts",
    )
  }

  @Test
  fun resizeSelectsTheFullLayoutRatherThanOnlyStretchingTheIcon() {
    val card = card("tile", "light.kitchen")
    val snapshot = snapshotOf(entity("light.kitchen", "on"))
    fun textLayouts(width: Int, height: Int): Int {
      val document = bake(card, snapshot, width, height)
      val operations = arrayListOf<Operation>()
      RemoteComposeBuffer.fromInputStream(platformWidgetDocument(document.bytes).inputStream())
        .inflateFromBuffer(operations)
      return operations.count {
        it is androidx.compose.remote.core.operations.layout.managers.TextLayout
      }
    }
    assertEquals(1, textLayouts(72, 84), "Compact shows the state beneath the icon")
    assertEquals(2, textLayouts(144, 168), "Wide widgets show both the editable name and state")
  }

  @Test
  fun oneCellListsBakeTheFirstEntityAndResizeRevealsMore() {
    val snapshot = snapshotOf(entity("light.kitchen", "on"), entity("sensor.hall", "23.5"))
    for (type in listOf("entities", "glance")) {
      val card =
        CardConfig(
          type,
          kotlinx.serialization.json.Json.parseToJsonElement(
            """{"type":"$type","entities":[{"entity":"light.kitchen","name":"Kitchen"},"sensor.hall"]}"""
          ) as JsonObject,
        )
      val compact = bake(card, snapshot, 72, 84)
      val roomy = bake(card, snapshot, 288, 252)
      fun text(bytes: ByteArray) = String(bytes, Charsets.UTF_8)
      assertTrue("On" in text(compact.bytes), "$type must show the first entity state at 1x1")
      assertFalse("23.5" in text(compact.bytes), "$type must not cram the second row into 1x1")
      assertTrue("23.5" in text(roomy.bytes), "$type resize must reveal the other entities")
      assertTrue(RcDocumentCodec.decode(compact.bytes, RcWireLimits()).namedVariables.isEmpty())
      assertTrue(
        compact.actions.values.any { it != HaAction.None },
        "$type keeps the first entity action",
      )
      val off =
        bake(
          card,
          snapshotOf(entity("light.kitchen", "off"), entity("sensor.hall", "23.5")),
          72,
          84,
        )
      assertFalse(compact.bytes.contentEquals(off.bytes), "$type state must refresh at 1x1")
    }
  }

  @Test
  fun oneCellTileMatchesDirectCaptureWithTheLiveOffTint() =
    assertWidgetParity(
      "one-cell-tile-off",
      card("tile", "light.kitchen"),
      snapshotOf(entity("light.kitchen", "off")),
      widthDp = 72,
      heightDp = 84,
      played = true,
    )

  @Test
  fun twoCellTileMatchesDirectCaptureAfterResizing() =
    assertWidgetParity(
      "two-cell-tile-off",
      card("tile", "light.kitchen"),
      snapshotOf(entity("light.kitchen", "off", "friendly_name" to "Kitchen")),
      widthDp = 144,
      heightDp = 84,
      played = true,
    )

  @Test
  fun oneCellEntitiesMatchesDirectCaptureWithTheLiveOffTint() =
    assertWidgetParity(
      "one-cell-entities-off",
      CardConfig(
        "entities",
        kotlinx.serialization.json.Json.parseToJsonElement(
          """{"type":"entities","entities":["light.kitchen","sensor.hall"]}"""
        ) as JsonObject,
      ),
      snapshotOf(entity("light.kitchen", "off"), entity("sensor.hall", "23.5")),
      widthDp = 72,
      heightDp = 84,
      played = true,
    )

  @get:Rule val compose = createComposeRule()

  private val registry = CardDesignTemplates.defaultTemplateRegistry
  // Match the player's device density: capturing at 420dpi then playing at Robolectric's
  // default 160dpi clips text and icons even when the baked/direct pixels match.
  private val density =
    org.robolectric.RuntimeEnvironment.getApplication().resources.configuration.densityDpi
  private val widthDp = 180
  private val heightDp = 80

  private fun card(type: String, entity: String) =
    CardConfig(
      type = type,
      raw =
        buildJsonObject {
          put("type", type)
          put("entity", entity)
        },
    )

  private fun snapshotOf(vararg states: EntityState) =
    HaSnapshot(states = states.associateBy { it.entityId })

  private fun entity(id: String, state: String, vararg attrs: Pair<String, String>) =
    EntityState(id, state, JsonObject(attrs.associate { (k, v) -> k to JsonPrimitive(v) }))

  @Test
  fun aLookIsPartOfTheKeyAndRoundTrips() {
    val card = card("tile", "light.kitchen")
    val snapshot = snapshotOf(entity("light.kitchen", "on"))
    fun key(look: LauncherLook) =
      LauncherDesigns.keyFor(card, snapshot, widthDp, heightDp, false, true, registry, look)!!
    // The classic system look keeps the key widgets always had.
    assertEquals("widget:system:light", key(LauncherLook()).theme)
    val looks =
      listOf(
        LauncherLook(HaUberTheme.Lcd),
        LauncherLook(HaUberTheme.Lcd, ThemeStyle.TerrazzoHome),
        LauncherLook(HaUberTheme.Ikea),
        LauncherLook(HaUberTheme.Ikea, ThemeStyle.TerrazzoHome),
        LauncherLook(HaUberTheme.Classic, ThemeStyle.entries.last()),
      )
    for (look in looks) {
      val theme = key(look).theme
      assertEquals(look, LauncherLook.parse(theme.split(':').drop(3)), theme)
    }
    assertEquals(looks.size, looks.map { key(it) }.distinct().size)
  }

  @Test
  fun aStyledWidgetBakesAndDiffersFromClassic() {
    val card = card("tile", "light.kitchen")
    val snapshot = snapshotOf(entity("light.kitchen", "on"))
    val classic = bake(card, snapshot)
    for (look in
      listOf(
        LauncherLook(HaUberTheme.Lcd),
        LauncherLook(HaUberTheme.Lcd, ThemeStyle.TerrazzoHome),
        LauncherLook(HaUberTheme.Ikea),
        LauncherLook(HaUberTheme.Ikea, ThemeStyle.TerrazzoHome),
      )) {
      val styled = bake(card, snapshot, look = look)
      assertFalse(classic.bytes.contentEquals(styled.bytes), "$look draws differently")
    }
  }

  @Test
  fun ikeaRemoteBakesReadingsAndExplicitPowerActions() {
    val light = card("light", "light.reading")
    val snapshot = snapshotOf(entity("light.reading", "on", "brightness" to "128"))
    for ((width, height) in listOf(180 to 80, 360 to 290)) {
      val baked = bake(light, snapshot, width, height, LauncherLook(HaUberTheme.Ikea))
      val document = RcDocumentCodec.decode(baked.bytes, RcWireLimits())
      assertTrue(document.namedVariables.none { "light.s0" in it.name }, "widget values are baked")
      assertTrue(baked.actions.isNotEmpty())
      if (width >= 260) {
        val services = baked.actions.values.filterIsInstance<HaAction.CallService>()
        assertTrue(services.all { it.entityId == "light.reading" })
        assertTrue(services.any { it.service == "turn_off" })
        assertTrue(services.any { it.service == "turn_on" && it.serviceData.isEmpty() })
        assertEquals(
          setOf("103", "153"),
          services
            .filter { it.serviceData.isNotEmpty() }
            .map { it.serviceData["brightness"].toString() }
            .toSet(),
        )
      }
    }
  }

  @Test
  fun consoleControlsBakeAtCompactSizesAndKeepTheirActions() {
    val snapshot = snapshotOf(entity("switch.fan", "on", "friendly_name" to "Fan"))
    for (type in listOf("tile", "button")) for ((width, height) in listOf(96 to 64, 180 to 48)) {
      val baked =
        bake(
          card(type, "switch.fan"),
          snapshot,
          width,
          height,
          LauncherLook(HaUberTheme.Console),
        )
      val document = RcDocumentCodec.decode(baked.bytes, RcWireLimits())
      assertTrue(
        document.namedVariables.none { "switch.s0" in it.name },
        "$type at $width × $height has its state baked",
      )
      val cardActions = baked.actions.values - WidgetOpenApp
      assertTrue(cardActions.isNotEmpty(), "$type retains its action")
      cardActions.forEach { assertEquals(defaultTapActionFor("switch.fan"), it) }
    }
  }

  @Test
  fun consoleThermostatBakesReadingsAndRealStepperActions() {
    val card = card("thermostat", "climate.studio")
    val snapshot =
      snapshotOf(
        entity(
          "climate.studio",
          "heat",
          "current_temperature" to "19",
          "temperature" to "22",
          "friendly_name" to "Studio",
        )
      )
    for ((width, height) in listOf(180 to 80, 360 to 290)) {
      val baked = bake(card, snapshot, width, height, LauncherLook(HaUberTheme.Console))
      val document = RcDocumentCodec.decode(baked.bytes, RcWireLimits())
      assertTrue(
        document.namedVariables.none { "climate.s0" in it.name },
        "all climate readings are baked",
      )
      if (width >= 260) {
        val steps = baked.actions.values.filterIsInstance<HaAction.CallService>()
        assertEquals(2, steps.size)
        steps.forEach {
          assertEquals("climate.studio", it.entityId)
          assertEquals("set_temperature", it.service)
        }
        assertEquals(
          setOf("21.5", "22.5"),
          steps.map { it.serviceData["temperature"].toString() }.toSet(),
        )
      }
    }
  }

  private fun bake(
    card: CardConfig,
    snapshot: HaSnapshot,
    widthDp: Int = this.widthDp,
    heightDp: Int = this.heightDp,
    look: LauncherLook = LauncherLook(),
  ): LauncherDesigns.Baked {
    val context = org.robolectric.RuntimeEnvironment.getApplication()
    return assertNotNull(
      LauncherDesigns.render(
        context,
        card,
        snapshot,
        widthDp,
        heightDp,
        density,
        dark = false,
        systemTheme = false,
        registry = registry,
        look = look,
      )
    )
  }

  @Test
  fun launcherGlanceBakesEveryEntity() {
    val entities = (1..5).map { entity("sensor.room_$it", "${it * 10 + 1}") }
    val card =
      CardConfig(
        "glance",
        kotlinx.serialization.json.Json.parseToJsonElement(
          """{"type":"glance","entities":[${entities.joinToString { "\"${it.entityId}\"" }}]}"""
        ) as JsonObject,
      )
    val baked = bake(card, snapshotOf(*entities.toTypedArray()), 288, 336)
    val bytes = String(baked.bytes, Charsets.ISO_8859_1)
    entities.forEach {
      assertTrue(it.state in bytes, "${it.entityId} must remain in the baked document")
    }
  }

  @Test
  fun bakedWidgetHoldsValuesNotBindings() {
    val card = card("tile", "light.kitchen")
    val snapshot = snapshotOf(entity("light.kitchen", "on", "friendly_name" to "Kitchen"))
    val baked = bake(card, snapshot)
    val document = RcDocumentCodec.decode(baked.bytes, RcWireLimits())
    val slotNames = document.namedVariables.map { it.name }.filter { "light.s0" in it }
    assertEquals(emptyList(), slotNames, "every slot binding is baked")
    assertTrue("Kitchen" in String(baked.bytes, Charsets.ISO_8859_1), "the name is baked in")
    // The tile's tap reaches the real light, never the template's slot.
    // Beside the card's taps, the widget surface opens the app.
    assertTrue(WidgetOpenApp in baked.actions.values, "the widget opens the app")
    val cardActions = baked.actions.values - WidgetOpenApp
    assertTrue(cardActions.isNotEmpty())
    for (action in cardActions) {
      assertEquals(defaultTapActionFor("light.kitchen"), action)
    }
  }

  @Test
  fun bakedWidgetMatchesADirectCapture() =
    assertWidgetParity(
      "tile",
      card("tile", "sensor.temp"),
      snapshotOf(
        entity(
          "sensor.temp",
          "21.5",
          "friendly_name" to "Living room",
          "device_class" to "temperature",
          "unit_of_measurement" to "°C",
        )
      ),
    )

  /**
   * A widget of an unavailable entity carries the uber theme's unavailable treatment: its design
   * key gains `unavailable`, so the baked template is captured with the entity unavailable and
   * draws the faded card and its label exactly as a direct capture does.
   */
  @Test
  fun bakedWidgetOfAnUnavailableEntityShowsTheThemesTreatment() {
    val card = card("tile", "sensor.temp")
    val snapshot =
      snapshotOf(entity("sensor.temp", "unavailable", "friendly_name" to "Living room"))
    assertEquals(
      "1",
      ee.schimke.ha.design.DesignProperties.parse(
          assertNotNull(
            registry.converterFor("tile")?.framedDesignProperties(card, snapshot)?.shape
          )
        )[UNAVAILABLE_DESIGN_PROPERTY],
      "the design key marks the entity unavailable",
    )
    assertWidgetParity("tile-unavailable", card, snapshot, played = true)
    assertTrue(
      compose.onAllNodesWithText("Unavailable").fetchSemanticsNodes().isNotEmpty(),
      "the baked widget shows the label",
    )
  }

  /** A direct Fixed-mode widget capture of [card], as the provider's fallback path builds it. */
  private fun directWidget(card: CardConfig, snapshot: HaSnapshot, widthDp: Int, heightDp: Int) =
    runBlocking {
      captureSingleRemoteDocument(
          context = org.robolectric.RuntimeEnvironment.getApplication(),
          creationDisplayInfo =
            RemoteCreationDisplayInfo(widthDp * density / 160, heightDp * density / 160, density),
          profile = widgetsProfile,
        ) {
          LauncherWidgetContent(
            registry,
            widgetsProfile,
            dark = false,
            WidgetActionRegistry(snapshot.states.mapValues { (_, e) -> formatState(e) }),
            card,
            snapshot,
            captureSizeDp = WidgetCaptureSizeDp(widthDp, heightDp),
          )
        }
        .bytes
    }

  /**
   * Widgets never receive bindings, so they carry no availability look: no `<id>.is_available`
   * (which a button's or entity's two size tiers would otherwise both register, colliding as
   * state-layout siblings), and no availability colour selects, which the bake leaves as colour
   * expressions that Android 16's platform widget player doesn't draw reliably.
   */
  @Test
  fun widgetsCarryNoAvailabilityBinding() {
    val sensors = (1..5).map { "sensor.room_$it" }
    val snapshot =
      snapshotOf(
        *sensors
          .map { entity(it, if (it == sensors.last()) "unavailable" else "12") }
          .toTypedArray(),
        entity("switch.boiler", "unavailable", "friendly_name" to "Boiler"),
      )
    val list = sensors.joinToString { "\"$it\"" }
    val cards =
      listOf(
        card("button", sensors.last()),
        card("entity", sensors.last()),
        card("tile", sensors.last()),
        card("entity", "switch.boiler"),
        CardConfig(
          "glance",
          kotlinx.serialization.json.Json.parseToJsonElement(
            """{"type":"glance","entities":[$list]}"""
          ) as JsonObject,
        ),
        CardConfig(
          "entities",
          kotlinx.serialization.json.Json.parseToJsonElement(
            """{"type":"entities","entities":[$list]}"""
          ) as JsonObject,
        ),
      )
    for (card in cards) {
      // Both tiers of the size ladders: icon-only and full.
      for ((w, h) in listOf(80 to 60, 288 to 252)) {
        val names =
          RcDocumentCodec.decode(directWidget(card, snapshot, w, h), RcWireLimits())
            .namedVariables
            .map { it.name }
        assertTrue(
          names.none { it.endsWith(".is_available") },
          "${card.type} ${w}x$h widget registers no availability: $names",
        )
      }
    }
    // A glance of read-only sensors has no state colour to select: its widget is all constants.
    val glance = bake(cards[4], snapshot, 288, 336)
    val operations = arrayListOf<Operation>()
    RemoteComposeBuffer.fromInputStream(platformWidgetDocument(glance.bytes).inputStream())
      .inflateFromBuffer(operations)
    assertEquals(
      emptyList(),
      operations.filterIsInstance<androidx.compose.remote.core.operations.ColorExpression>().map {
        it.toString()
      },
    )
  }

  @Test
  fun bakedGaugeWidgetMatchesADirectCapture() =
    assertWidgetParity(
      "gauge",
      CardConfig(
        type = "gauge",
        raw =
          buildJsonObject {
            put("type", "gauge")
            put("entity", "sensor.cpu")
            put(
              "severity",
              buildJsonObject {
                put("green", 0)
                put("yellow", 50)
                put("red", 80)
              },
            )
          },
      ),
      snapshotOf(
        entity("sensor.cpu", "86", "friendly_name" to "CPU", "unit_of_measurement" to "%")
      ),
    )

  @Test
  fun alarmWidgetWithATypedPinResolvesToTheRealPanel() {
    val card =
      CardConfig(
        type = "alarm-panel",
        raw =
          buildJsonObject {
            put("type", "alarm-panel")
            put("entity", "alarm_control_panel.home")
            put("code_length", 4)
          },
      )
    val snapshot =
      snapshotOf(entity("alarm_control_panel.home", "armed_away", "friendly_name" to "Home"))
    val baked = bake(card, snapshot, widthDp = 360, heightDp = 400)
    val pins = baked.actions.values.filterIsInstance<HaAction.AlarmPin>()
    assertTrue(pins.isNotEmpty(), "the widget accumulates the PIN in-document")
    assertTrue(pins.all { it == HaAction.AlarmPin("alarm_control_panel.home", "") })
    assertTrue(
      baked.actions.values.none { "alarm_control_panel.s0" in it.toString() },
      "no action names the template's slot",
    )
  }

  @Test
  fun bakedPictureWidgetEmbedsTheEntitysOwnImage() {
    val url = "/api/camera_proxy/camera.driveway?token=abc"
    val card = card("picture-entity", "camera.driveway")
    val snapshot =
      snapshotOf(
        entity("camera.driveway", "idle", "friendly_name" to "Driveway", "entity_picture" to url)
      )
    val png =
      Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).let { bitmap ->
        android.graphics.Canvas(bitmap).drawColor(android.graphics.Color.YELLOW)
        java.io
          .ByteArrayOutputStream()
          .also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
          .toByteArray()
      }
    val fetched = mutableListOf<String>()
    val context = org.robolectric.RuntimeEnvironment.getApplication()
    val baked =
      assertNotNull(
        LauncherDesigns.render(
          context,
          card,
          snapshot,
          widthDp,
          heightDp,
          density,
          dark = false,
          systemTheme = false,
          registry = registry,
          fetchImage = {
            fetched += it
            if (it == url) png else null
          },
        )
      )
    // Only the card's own picture was fetched, and the widget no longer names any URL.
    assertEquals(listOf(url), fetched)
    val document = RcDocumentCodec.decode(baked.bytes, RcWireLimits())
    assertEquals(emptyList(), document.bitmapUrls)
    // Without the picture there is nothing to embed: the direct capture is kept.
    assertEquals(
      null,
      LauncherDesigns.render(
        context,
        card,
        snapshot,
        widthDp,
        heightDp,
        density,
        dark = false,
        systemTheme = false,
        registry = registry,
        fetchImage = { null },
      ),
    )
  }

  /**
   * An entities widget roomy enough for its expanded rows, with a light and a switch that are on:
   * the baked template must draw them on (accent and switch), as the direct capture does, not in
   * the off state of the template's placeholder entities.
   */
  @Test
  fun bakedExpandedEntitiesWidgetShowsTheLiveOnOffState() =
    assertWidgetParity(
      "entities-expanded",
      CardConfig(
        "entities",
        kotlinx.serialization.json.Json.parseToJsonElement(
          """{"type":"entities","title":"Living Room","entities":
            ["sensor.temp","light.lamp","switch.coffee","binary_sensor.door"]}"""
        ) as JsonObject,
      ),
      snapshotOf(
        entity("sensor.temp", "21.5", "friendly_name" to "Living Room"),
        entity("light.lamp", "on", "friendly_name" to "Office lamp"),
        entity("switch.coffee", "on", "friendly_name" to "Coffee maker"),
        entity(
          "binary_sensor.door",
          "on",
          "friendly_name" to "Front door",
          "device_class" to "door",
        ),
      ),
      widthDp = 288,
      heightDp = 252,
      played = true,
    )

  @Test
  fun shortWideClimateWidgetsKeepTheReadingVisibleWithoutHiddenSteppers() {
    val cases =
      listOf(
        Triple(
          "thermostat",
          "climate.studio",
          entity(
            "climate.studio",
            "heat",
            "current_temperature" to "19",
            "temperature" to "22",
            "friendly_name" to "Studio",
          ),
        ),
        Triple(
          "humidifier",
          "humidifier.bedroom",
          entity(
            "humidifier.bedroom",
            "on",
            "current_humidity" to "45",
            "humidity" to "55",
            "friendly_name" to "Bedroom",
          ),
        ),
        Triple(
          "light",
          "light.kitchen",
          entity("light.kitchen", "on", "brightness" to "128", "friendly_name" to "Kitchen"),
        ),
      )
    val (type, id, state) = cases.first()
    renderRegression(
      "short-wide-climate",
      bakeRegression(card(type, id), snapshotOf(state), 304, 80),
      304,
      80,
    )
    for ((kind, entityId, entityState) in cases) {
      for (family in listOf(HaUberTheme.Classic, HaUberTheme.Console, HaUberTheme.Ikea)) {
        val snapshot = snapshotOf(entityState)
        val config = card(kind, entityId)
        val look = LauncherLook(family)
        val short = bake(config, snapshot, 304, 80, look)
        assertTrue(
          short.actions.values.none { it is HaAction.CallService },
          "$kind/${family.id}: a short widget must not capture off-screen steppers",
        )
        val full = bake(config, snapshot, 304, 320, look)
        assertTrue(
          full.actions.values.filterIsInstance<HaAction.CallService>().size >= 2,
          "$kind/${family.id}: a tall widget retains controls",
        )
      }
    }
  }

  @Test
  fun mediaWidgetShowsAContinuousSeekTrackForItsPosition() {
    val snapshot =
      snapshotOf(
        entity(
          "media_player.office",
          "paused",
          "friendly_name" to "Office speaker",
          "media_title" to "The Anthem",
          "media_artist" to "Good Charlotte",
          "media_position" to "100",
          "media_duration" to "200",
        )
      )
    val baked = bakeRegression(card("media-control", "media_player.office"), snapshot, 320, 180)
    val pixels = renderRegression("media-seek-track", baked, 320, 180)
    var longest = 0
    for (y in 0 until pixels.height) {
      var run = 0
      for (x in 0 until pixels.width) {
        if (pixels[x, y] == androidx.compose.ui.graphics.Color(0xFF673AB7)) {
          run++
          longest = maxOf(longest, run)
        } else run = 0
      }
    }
    assertTrue(
      longest > 60 * pixels.width / 320,
      "a half-full seek track must draw a continuous accent run, got $longest px",
    )
    assertEquals(
      3,
      baked.actions.values.filterIsInstance<HaAction.CallService>().size,
      "transport actions remain present",
    )
  }

  // Match these pixel assertions to the mdpi Compose host, rather than the 420dpi documents
  // used by the separate platform-encoding and action-parity tests above.
  private fun bakeRegression(card: CardConfig, snapshot: HaSnapshot, width: Int, height: Int) =
    assertNotNull(
      LauncherDesigns.render(
        org.robolectric.RuntimeEnvironment.getApplication(),
        card,
        snapshot,
        width,
        height,
        160,
        dark = false,
        systemTheme = false,
        registry = registry,
      )
    )

  private fun renderRegression(
    name: String,
    baked: LauncherDesigns.Baked,
    width: Int,
    height: Int,
  ): androidx.compose.ui.graphics.PixelMap {
    compose.setContent {
      Box(Modifier.size(width.dp, height.dp).testTag("widget")) {
        HaRcComposePlayer(
          documentBytes = baked.bytes,
          modifier = Modifier.size(width.dp, height.dp),
          fallback = { error("regression document must render") },
        )
      }
    }
    compose.waitForIdle()
    return capture(name)
  }

  /**
   * With [played], both documents play in a player of the widget's size, as a launcher plays a
   * widget, rather than the wrap-height card preview: a card that fills its widget (the expanded
   * entities rows share its height) draws nothing in a wrap-height player.
   */
  private fun assertWidgetParity(
    name: String,
    card: CardConfig,
    snapshot: HaSnapshot,
    widthDp: Int = this.widthDp,
    heightDp: Int = this.heightDp,
    played: Boolean = false,
  ) {
    val context = org.robolectric.RuntimeEnvironment.getApplication()
    val baked = bake(card, snapshot, widthDp, heightDp)
    val widthPx = widthDp * density / 160
    val heightPx = heightDp * density / 160
    val direct = runBlocking {
      captureSingleRemoteDocument(
          context = context,
          creationDisplayInfo = RemoteCreationDisplayInfo(widthPx, heightPx, density),
          profile = widgetsProfile,
        ) {
          LauncherWidgetContent(
            registry,
            widgetsProfile,
            dark = false,
            // As the provider builds it: the tap metadata's state seed is the live state.
            WidgetActionRegistry(snapshot.states.mapValues { (_, e) -> formatState(e, snapshot) }),
            card,
            snapshot,
            captureSizeDp = WidgetCaptureSizeDp(widthDp, heightDp),
          )
        }
        .bytes
    }

    var bytes by mutableStateOf(direct)
    compose.setContent {
      Box(Modifier.size(widthDp.dp, heightDp.dp).testTag("widget")) {
        if (played) {
          androidx.compose.runtime.key(bytes) {
            HaRcComposePlayer(
              documentBytes = bytes,
              modifier = Modifier.size(widthDp.dp, heightDp.dp),
              fallback = { error("the widget plays in the Compose player") },
            )
          }
          return@Box
        }
        CachedCardPreview(
          cacheKey = "widget-$name",
          profile = widgetsProfile,
          template =
            CardTemplatePlayback(
              bytes,
              { _, _ -> CardSnapshotBindings(emptyMap(), emptyMap(), emptyMap(), emptyMap()) },
              HaAction.None,
            ),
        ) {}
      }
    }
    compose.waitForIdle()
    val directPixels = capture("direct-$name")
    compose.runOnIdle { bytes = baked.bytes }
    compose.waitForIdle()
    val bakedPixels = capture("baked-$name")

    var differing = 0
    for (y in 0 until directPixels.height) for (x in 0 until directPixels.width) {
      if (directPixels[x, y] != bakedPixels[x, y]) differing++
    }
    assertEquals(0, differing, "$name: pixels differing between a direct and a baked widget")
  }

  private fun capture(name: String) =
    compose
      .onNodeWithTag("widget")
      .captureToImage()
      .also { image ->
        val dir = File("build/launcher-designs").also { it.mkdirs() }
        dir.resolve("$name.png").outputStream().use {
          image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
      }
      .toPixelMap()
}
