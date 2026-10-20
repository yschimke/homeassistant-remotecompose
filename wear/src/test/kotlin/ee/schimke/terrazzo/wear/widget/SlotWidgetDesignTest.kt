@file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

package ee.schimke.terrazzo.wear.widget

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.glance.wear.WearWidgetData
import androidx.glance.wear.WearWidgetDocument
import androidx.glance.wear.core.ContainerInfo
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.core.WidgetInstanceId
import androidx.wear.compose.remote.material3.RemoteColorScheme
import ee.schimke.composeai.rcplayer.edit.RcTargetProfiles
import ee.schimke.composeai.rcplayer.edit.bitmapUrls
import ee.schimke.composeai.rcplayer.edit.namedVariables
import ee.schimke.composeai.rcplayer.edit.validateFor
import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CachedCardPreview
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.CardTemplatePlayback
import ee.schimke.ha.rc.HaRcComposePlayer
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.terrazzo.wear.ui.terrazzoWearColorScheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Wear widgets from card designs: Glance Wear captures the slot's template, and the card's values
 * are baked in. The baked widget must hold no slot bindings and draw what a direct capture draws.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SlotWidgetDesignTest {

  @get:Rule val compose = createComposeRule()

  private val registry = defaultRegistry()
  private val style = ThemeStyle.entries.first()
  private val colors = RemoteColorScheme(terrazzoWearColorScheme(style))

  private val params =
    WearWidgetParams(
      instanceId = WidgetInstanceId("widgets", 1),
      containerType = ContainerInfo.CONTAINER_TYPE_LARGE,
      widthDp = 200f,
      heightDp = 112f,
      verticalPaddingDp = 8f,
      horizontalPaddingDp = 8f,
      cornerRadiusDp = 26f,
    )

  private fun card(type: String, entity: String) =
    CardConfig(
      type = type,
      raw =
        buildJsonObject {
          put("type", type)
          put("entity", entity)
        },
    )

  private fun entity(id: String, state: String, vararg attrs: Pair<String, String>) =
    EntityState(id, state, JsonObject(attrs.associate { (k, v) -> k to JsonPrimitive(v) }))

  private fun data(
    card: CardConfig,
    snapshot: HaSnapshot,
    fetchImage: (String) -> ByteArray? = { null },
  ): WearWidgetData =
    slotWidgetData(registry, colors, style, card, snapshot, 0, fetchImage = fetchImage)

  private val cameraUrl = "/api/camera_proxy/camera.driveway?token=abc"

  private val camera =
    HaSnapshot(
      states =
        mapOf(
          "camera.driveway" to
            entity(
              "camera.driveway",
              "idle",
              "friendly_name" to "Driveway",
              "entity_picture" to cameraUrl,
            )
        )
    )

  /** A solid yellow PNG: the picture the phone synced. */
  private val yellowPng: ByteArray =
    Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).let { bitmap ->
      android.graphics.Canvas(bitmap).drawColor(android.graphics.Color.YELLOW)
      java.io
        .ByteArrayOutputStream()
        .also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        .toByteArray()
    }

  private fun capture(data: WearWidgetData): ByteArray = runBlocking {
    data.captureRawContent(RuntimeEnvironment.getApplication(), params, false).rcDocument
  }

  private fun assertBaked(
    card: CardConfig,
    snapshot: HaSnapshot,
    slot: String,
    text: String,
    fetchImage: (String) -> ByteArray? = { null },
  ): ByteArray {
    val data = data(card, snapshot, fetchImage)
    assertFalse(data is WearWidgetDocument, "${card.type} renders from its design")
    val bytes = capture(data)
    val document = RcDocumentCodec.decode(bytes, RcWireLimits())
    assertEquals(
      emptyList(),
      // A slot picture keeps its name with the embedded bytes as its value (bitmapUrls below):
      // a host may swap it, but has nothing to supply.
      document.namedVariables
        .map { it.name }
        .filter { slot in it && !it.endsWith(".entity_picture") },
      "every slot binding is baked",
    )
    assertEquals(emptyList(), document.bitmapUrls, "a Wear widget names no image URL")
    assertEquals(
      emptyList(),
      document.validateFor(RcTargetProfiles.WEAR_WIDGETS),
      "the baked widget stays in the Wear widget op set",
    )
    assertTrue(text in String(bytes, Charsets.ISO_8859_1), "the value is baked in")
    return bytes
  }

  @Test
  fun bakingLeavesTheCachedTemplateUnchanged() {
    val card = card("picture-entity", "camera.driveway")
    val snapshot =
      HaSnapshot(
        states =
          mapOf(
            "camera.driveway" to entity("camera.driveway", "idle", "friendly_name" to "Driveway")
          )
      )
    val first = capture(data(card, snapshot))
    val second = capture(data(card, snapshot))
    assertTrue(
      first.contentEquals(second),
      "a second bake of the cached template matches the first",
    )
  }

  @Test
  fun aHostChangingItsInsetsOrCornersGetsANewTemplate() {
    val card = card("tile", "light.kitchen")
    val snapshot = HaSnapshot(states = mapOf("light.kitchen" to entity("light.kitchen", "on")))
    val original = capture(data(card, snapshot))
    var displayed by mutableStateOf(original)
    compose.setContent {
      Box(Modifier.size(params.widthDp.dp, params.heightDp.dp).testTag("widget")) {
        key(displayed) {
          HaRcComposePlayer(
            documentBytes = displayed,
            modifier = Modifier.size(params.widthDp.dp, params.heightDp.dp),
            fallback = { error("host geometry document must play") },
          )
        }
      }
    }
    for ((horizontal, vertical, corner) in
      listOf(Triple(24f, 8f, 26f), Triple(8f, 20f, 26f), Triple(8f, 8f, 4f))) {
      val changedParams =
        WearWidgetParams(
          instanceId = params.instanceId,
          containerType = params.containerType,
          widthDp = params.widthDp,
          heightDp = params.heightDp,
          horizontalPaddingDp = horizontal,
          verticalPaddingDp = vertical,
          cornerRadiusDp = corner,
        )
      val changed = runBlocking {
        data(card, snapshot)
          .captureRawContent(RuntimeEnvironment.getApplication(), changedParams, false)
          .rcDocument
      }
      val fresh = runBlocking {
        slotWidgetData(registry, colors, Any(), card, snapshot, 0)
          .captureRawContent(RuntimeEnvironment.getApplication(), changedParams, false)
          .rcDocument
      }
      compose.runOnIdle { displayed = changed }
      compose.waitForIdle()
      val cachedPixels = pixels("geometry-cached-$horizontal-$vertical-$corner")
      compose.runOnIdle { displayed = fresh }
      compose.waitForIdle()
      val freshPixels = pixels("geometry-fresh-$horizontal-$vertical-$corner")
      var differing = 0
      for (y in 0 until freshPixels.height) for (x in 0 until freshPixels.width) {
        if (freshPixels[x, y] != cachedPixels[x, y]) differing++
      }
      assertEquals(0, differing, "cached capture must match fresh host geometry")
    }
    assertTrue(original.contentEquals(capture(data(card, snapshot))))
  }

  @Test
  fun tileBakesItsValues() {
    assertBaked(
      card("tile", "light.kitchen"),
      HaSnapshot(
        states =
          mapOf("light.kitchen" to entity("light.kitchen", "on", "friendly_name" to "Kitchen"))
      ),
      "light.s0",
      "Kitchen",
    )
  }

  @Test
  fun changingOnlyThePhoneOverrideInvalidatesTheTemplate() {
    val card = card("sensor", "sensor.temp")
    val snapshot =
      HaSnapshot(
        states =
          mapOf(
            "sensor.temp" to
              entity(
                "sensor.temp",
                "21.5",
                "device_class" to "temperature",
                "unit_of_measurement" to "°C",
              )
          )
      )
    fun render(override: String) =
      capture(
        slotWidgetData(
          registry,
          colors,
          style,
          card,
          snapshot,
          0,
          wearWidgetCardStyle(override, "braun"),
        )
      )
    val inherited = render("")
    val overridden = render("lcd")
    assertFalse(
      inherited.contentEquals(overridden),
      "an LCD override must replace the Braun template",
    )
    assertTrue(
      inherited.contentEquals(render("")),
      "clearing the override restores the watch design",
    )
  }

  @Test
  fun alternativeStylesBakeFreshReadingsAtBothWearSizes() {
    val card = card("sensor", "sensor.temp")
    data class Frame(val name: String, val height: Float, val bytes: ByteArray)
    val frames = mutableListOf<Frame>()
    fun snapshot(value: String) =
      HaSnapshot(
        states =
          mapOf(
            "sensor.temp" to
              entity(
                "sensor.temp",
                value,
                "friendly_name" to "Living room",
                "device_class" to "temperature",
                "unit_of_measurement" to "°C",
              )
          )
      )
    for (uber in HaUberTheme.all) {
      for ((container, height) in
        listOf(
          ContainerInfo.CONTAINER_TYPE_SMALL to 60f,
          ContainerInfo.CONTAINER_TYPE_LARGE to 112f,
        )) {
        val sized =
          WearWidgetParams(
            instanceId = params.instanceId,
            containerType = container,
            widthDp = params.widthDp,
            heightDp = height,
            horizontalPaddingDp = params.horizontalPaddingDp,
            verticalPaddingDp = params.verticalPaddingDp,
            cornerRadiusDp = params.cornerRadiusDp,
          )
        fun captureReading(value: String) = runBlocking {
          slotWidgetData(registry, colors, style to uber.id, card, snapshot(value), 0, uber)
            .captureRawContent(RuntimeEnvironment.getApplication(), sized, false)
            .rcDocument
        }
        val first = captureReading("21.5")
        val changed = captureReading("27.5")
        assertTrue("21.5" in String(first, Charsets.ISO_8859_1), "the initial reading is baked")
        assertTrue("27.5" in String(changed, Charsets.ISO_8859_1), "the new reading is baked")
        assertFalse(
          "No samples" in String(first, Charsets.ISO_8859_1),
          "Wear has no history: show the current measurement",
        )
        frames += Frame("style-${uber.id}-${height.toInt()}", height, first)
        assertFalse(first.contentEquals(changed), "${uber.id}/$height must refresh its baked value")
        for (bytes in listOf(first, changed)) {
          val document = RcDocumentCodec.decode(bytes, RcWireLimits())
          assertEquals(
            emptyList(),
            document.validateFor(RcTargetProfiles.WEAR_WIDGETS),
            "${uber.id}/$height",
          )
          assertFalse(
            document.namedVariables.any { it.name.startsWith("sensor.s0.") },
            "no unresolved entity slot",
          )
        }
      }
    }
    // Supplementary player evidence, explicitly not a system-host installation screenshot.
    var frame by mutableStateOf(frames.first())
    compose.setContent {
      Box(Modifier.size(200.dp, frame.height.dp).testTag("widget")) {
        key(frame.name) {
          HaRcComposePlayer(
            documentBytes = frame.bytes,
            modifier = Modifier.size(200.dp, frame.height.dp),
            fallback = { error("${frame.name} failed playback") },
          )
        }
      }
    }
    for (next in frames) {
      compose.runOnIdle { frame = next }
      compose.waitForIdle()
      pixels(next.name)
    }
  }

  @Test
  fun offlineGridWidgetsFollowPersistedSelectionAndFreshValuesAtBothSizes() = runBlocking {
    val context = RuntimeEnvironment.getApplication()
    val prefs = ee.schimke.terrazzo.wear.data.WearPrefs(context)
    val store = ee.schimke.terrazzo.wear.sync.WearOfflineStore(context)
    val raw =
      """{"type":"grid","columns":2,"square":false,"cards":[{"type":"button","entity":"light.kitchen","name":"Kitchen"},{"type":"button","entity":"light.office","name":"Office"}]}"""
    fun pin(override: String) {
      store.writePinned(
        ee.schimke.terrazzo.wearsync.proto.PinnedCardSet(
          cards =
            listOf(
              ee.schimke.terrazzo.wearsync.proto.PinnedCard(
                cardKey = "grid",
                card =
                  ee.schimke.terrazzo.wearsync.proto.CardSummary(
                    type = "grid",
                    rawJson = raw,
                    cardStyleOverride = override,
                  ),
              )
            )
        )
      )
    }
    fun live(state: String) {
      store.writeValues(
        ee.schimke.terrazzo.wearsync.proto.LiveValues(
          values =
            mapOf(
              "light.kitchen" to
                ee.schimke.terrazzo.wearsync.proto.EntityValue(
                  state = state,
                  friendlyName = "Kitchen",
                ),
              "light.office" to
                ee.schimke.terrazzo.wearsync.proto.EntityValue(
                  state = "off",
                  friendlyName = "Office",
                ),
            )
        )
      )
    }
    store.writeSlots(
      ee.schimke.terrazzo.wearsync.proto.WearWidgetSlots(
        slots =
          listOf(ee.schimke.terrazzo.wearsync.proto.WidgetSlot(slotIndex = 0, cardKey = "grid"))
      )
    )
    data class Frame(val name: String, val height: Float, val bytes: ByteArray)
    val frames = mutableListOf<Frame>()
    for ((container, height) in
      listOf(
        ContainerInfo.CONTAINER_TYPE_SMALL to 60f,
        ContainerInfo.CONTAINER_TYPE_LARGE to 112f,
      )) {
      val sized =
        WearWidgetParams(
          instanceId = params.instanceId,
          containerType = container,
          widthDp = 200f,
          heightDp = height,
          horizontalPaddingDp = 8f,
          verticalPaddingDp = 8f,
          cornerRadiusDp = 26f,
        )
      suspend fun render(): ByteArray =
        slotWidgetDataFromStore(
            context,
            ee.schimke.terrazzo.wear.sync.WearOfflineStore(context),
            0,
          )
          .captureRawContent(context, sized, false)
          .rcDocument
      prefs.setCardStyle("braun")
      pin("")
      live("on")
      val inherited = render()
      pin("lcd")
      val overridden = render()
      assertFalse(
        inherited.contentEquals(overridden),
        "offline pin style overrides the watch style",
      )
      live("off")
      val updated = render()
      assertFalse(overridden.contentEquals(updated), "a container rebakes current child values")
      pin("")
      val cleared = render()
      assertFalse(
        updated.contentEquals(cleared),
        "clearing the persisted override restores the watch style",
      )
      prefs.setCardStyle("console")
      val changedWatch = render()
      assertFalse(
        cleared.contentEquals(changedWatch),
        "watch setting changes invalidate the cached widget design",
      )
      for ((name, bytes) in
        listOf(
          "braun-on" to inherited,
          "lcd-on" to overridden,
          "lcd-off" to updated,
          "braun-off" to cleared,
          "console-off" to changedWatch,
        )) {
        val document = RcDocumentCodec.decode(bytes, RcWireLimits())
        assertEquals(emptyList(), document.validateFor(RcTargetProfiles.WEAR_WIDGETS), name)
        assertFalse(
          document.namedVariables.any { it.name.startsWith("light.s") },
          "all child slots are baked",
        )
        assertTrue("Kitchen" in String(bytes, Charsets.ISO_8859_1))
        assertTrue("Office" in String(bytes, Charsets.ISO_8859_1))
        frames += Frame("offline-grid-$name-${height.toInt()}", height, bytes)
      }
    }
    var frame by mutableStateOf(frames.first())
    compose.setContent {
      Box(Modifier.size(200.dp, frame.height.dp).testTag("widget")) {
        key(frame.name) {
          HaRcComposePlayer(
            documentBytes = frame.bytes,
            modifier = Modifier.size(200.dp, frame.height.dp),
            fallback = { error("${frame.name} failed playback") },
          )
        }
      }
    }
    for (next in frames) {
      compose.runOnIdle { frame = next }
      compose.waitForIdle()
      pixels(next.name)
    }
  }

  @Test
  fun pictureCardEmbedsItsSyncedPicture() {
    val fetched = mutableListOf<String>()
    val bytes =
      assertBaked(card("picture-entity", "camera.driveway"), camera, "camera.s0", "Driveway") {
        fetched += it
        if (it == cameraUrl) yellowPng else null
      }
    // Only the card's own picture was asked for, and the widget carries it inline.
    assertEquals(listOf(cameraUrl), fetched)
    val bitmaps =
      RcDocumentCodec.decode(bytes, RcWireLimits()).operations.filterIsInstance<RcBitmapData>()
    assertTrue(
      bitmaps.any {
        it.encoding == RcBitmapData.ENCODING_INLINE &&
          it.width == 64 &&
          it.height == 48 &&
          it.data.contentEquals(yellowPng)
      },
      "the synced PNG is embedded",
    )
    // And drawn: played as the watch face preview plays a widget, at its container's size.
    compose.setContent {
      Box(Modifier.size(params.widthDp.dp, params.heightDp.dp).testTag("widget")) {
        HaRcComposePlayer(
          documentBytes = bytes,
          modifier = Modifier.size(params.widthDp.dp, params.heightDp.dp),
          fallback = { error("the baked widget plays in the Compose player") },
        )
      }
    }
    compose.waitForIdle()
    val pixels = pixels("synced-picture")
    var yellow = 0
    for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
      val c = pixels[x, y]
      if (c.red > 0.9f && c.green > 0.9f && c.blue < 0.2f) yellow++
    }
    assertTrue(
      yellow > pixels.width * pixels.height / 3,
      "the synced picture fills the card: $yellow of ${pixels.width * pixels.height} pixels",
    )
  }

  @Test
  fun pictureCardWithoutASyncedPictureShowsItsIcon() {
    // Not synced yet: the direct capture, which draws the card's icon.
    val bytes = capture(data(card("picture-entity", "camera.driveway"), camera))
    val document = RcDocumentCodec.decode(bytes, RcWireLimits())
    assertEquals(emptyList(), document.bitmapUrls, "a Wear widget names no image URL")
    assertTrue("Driveway" in String(bytes, Charsets.ISO_8859_1))
  }

  @Test
  fun bakedTileMatchesADirectCapture() =
    assertParity(
      "tile",
      card("tile", "sensor.temp"),
      HaSnapshot(
        states =
          mapOf(
            "sensor.temp" to
              entity(
                "sensor.temp",
                "21.5",
                "friendly_name" to "Living room",
                "device_class" to "temperature",
                "unit_of_measurement" to "°C",
              )
          )
      ),
    )

  /** A picture card whose entity has no picture: its icon design, baked. */
  @Test
  fun bakedPicturelessCardMatchesADirectCapture() =
    assertParity(
      "picture-entity",
      card("picture-entity", "camera.driveway"),
      HaSnapshot(
        states =
          mapOf(
            "camera.driveway" to entity("camera.driveway", "idle", "friendly_name" to "Driveway")
          )
      ),
    )

  /**
   * An entities slot with a light that is on: the baked template must draw it on, as the direct
   * capture does, not in the off accent of the template's placeholder entities.
   */
  @Test
  fun bakedEntitiesShowTheLiveOnOffState() =
    assertPlayedParity(
      "entities",
      CardConfig(
        "entities",
        kotlinx.serialization.json.Json.parseToJsonElement(
          """{"type":"entities","title":"Living Room","entities":["light.lamp","switch.coffee"]}"""
        ) as JsonObject,
      ),
      HaSnapshot(
        states =
          mapOf(
            "light.lamp" to entity("light.lamp", "on", "friendly_name" to "Office lamp"),
            "switch.coffee" to entity("switch.coffee", "on", "friendly_name" to "Coffee maker"),
          )
      ),
    )

  /**
   * [assertParity] played as the watch face plays a widget, at its container's size: a card that
   * fills its slot (the entities rows share the slot's height) draws nothing in a wrap-height
   * player.
   */
  private fun assertPlayedParity(name: String, card: CardConfig, snapshot: HaSnapshot) {
    val data = data(card, snapshot)
    assertFalse(data is WearWidgetDocument, "${card.type} renders from its design")
    val direct = capture(slotDocumentForTest(card, snapshot))
    val baked = capture(data)
    var bytes by mutableStateOf(direct)
    compose.setContent {
      Box(Modifier.size(params.widthDp.dp, params.heightDp.dp).testTag("widget")) {
        key(bytes) {
          HaRcComposePlayer(
            documentBytes = bytes,
            modifier = Modifier.size(params.widthDp.dp, params.heightDp.dp),
            fallback = { error("the widget plays in the Compose player") },
          )
        }
      }
    }
    compose.waitForIdle()
    val directPixels = pixels("direct-$name")
    compose.runOnIdle { bytes = baked }
    compose.waitForIdle()
    val bakedPixels = pixels("baked-$name")
    var differing = 0
    for (y in 0 until directPixels.height) for (x in 0 until directPixels.width) {
      if (directPixels[x, y] != bakedPixels[x, y]) differing++
    }
    assertEquals(0, differing, "$name: pixels differing between a direct and a baked Wear widget")
  }

  private fun assertParity(name: String, card: CardConfig, snapshot: HaSnapshot) {
    val direct = capture(slotDocumentForTest(card, snapshot))
    val baked = capture(data(card, snapshot))
    var bytes by mutableStateOf(direct)
    compose.setContent {
      Box(Modifier.size(params.widthDp.dp, params.heightDp.dp).testTag("widget")) {
        // A fresh player per document: a player handed a new document in place keeps the old
        // one's text state, which a widget host never does.
        key(bytes) {
          CachedCardPreview(
            cacheKey = "wear-$name-${bytes.contentHashCode()}",
            template =
              CardTemplatePlayback(
                bytes,
                { _, _ -> CardSnapshotBindings(emptyMap(), emptyMap(), emptyMap(), emptyMap()) },
                HaAction.None,
              ),
          ) {}
        }
      }
    }
    compose.waitForIdle()
    val directPixels = pixels("direct-$name")
    compose.runOnIdle { bytes = baked }
    compose.waitForIdle()
    val bakedPixels = pixels("baked-$name")
    var differing = 0
    for (y in 0 until directPixels.height) for (x in 0 until directPixels.width) {
      if (directPixels[x, y] != bakedPixels[x, y]) differing++
    }
    assertEquals(0, differing, "$name: pixels differing between a direct and a baked Wear widget")
  }

  private fun slotDocumentForTest(card: CardConfig, snapshot: HaSnapshot): WearWidgetData =
    slotDirectDocument(registry, colors, card, snapshot, slotIndex = 0)

  private fun pixels(name: String) =
    compose
      .onNodeWithTag("widget")
      .captureToImage()
      .also { image ->
        val dir = File("build/wear-designs").also { it.mkdirs() }
        dir.resolve("$name.png").outputStream().use {
          image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
      }
      .toPixelMap()
}
