package ee.schimke.ha.rc

import android.graphics.Bitmap
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
import ee.schimke.ha.rc.components.WidgetActionRegistry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

  @get:Rule val compose = createComposeRule()

  private val registry = CardDesignTemplates.defaultTemplateRegistry
  private val density = 420
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

  private fun bake(card: CardConfig, snapshot: HaSnapshot): LauncherDesigns.Baked {
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
      )
    )
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
    assertTrue(baked.actions.isNotEmpty())
    for (action in baked.actions.values) {
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
    val baked = bake(card, snapshot)
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

  private fun assertWidgetParity(name: String, card: CardConfig, snapshot: HaSnapshot) {
    val context = org.robolectric.RuntimeEnvironment.getApplication()
    val baked = bake(card, snapshot)
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
            WidgetActionRegistry(snapshot.states.mapValues { (_, e) -> formatState(e) }),
            card,
            snapshot,
          )
        }
        .bytes
    }

    var bytes by mutableStateOf(direct)
    compose.setContent {
      Box(Modifier.size(widthDp.dp, heightDp.dp).testTag("widget")) {
        CachedCardPreview(
          cacheKey = "widget-$name",
          profile = widgetsProfile,
          template =
            CardTemplatePlayback(
              bytes,
              { CardSnapshotBindings(emptyMap(), emptyMap(), emptyMap(), emptyMap()) },
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
