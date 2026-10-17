package ee.schimke.ha.rc

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.rcplayer.edit.namedVariables
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.WidgetActionRegistry
import ee.schimke.ha.rc.components.haThemeFor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Climate demo's tiles drawn end to end: the secondary line, the `hvac_action` badge and the
 * `target-temperature` / `climate-hvac-modes` features reach the encoded document, and a `color:`
 * tile draws its colour. Each render is saved to `build/tile-captures/` for review.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TileFeaturesCaptureTest {

  @get:Rule val compose = createComposeRule()

  private val registry = defaultRegistry()

  private fun climate(id: String, name: String, action: String, current: Double, target: Double) =
    EntityState(
      id,
      "heat",
      JsonObject(
        mapOf(
          "friendly_name" to JsonPrimitive(name),
          "hvac_modes" to JsonArray(listOf(JsonPrimitive("heat"), JsonPrimitive("off"))),
          "min_temp" to JsonPrimitive(10),
          "max_temp" to JsonPrimitive(32),
          "current_temperature" to JsonPrimitive(current),
          "temperature" to JsonPrimitive(target),
          "hvac_action" to JsonPrimitive(action),
          "supported_features" to JsonPrimitive(401),
        )
      ),
    )

  private val kitchen = climate("climate.kids_room", "Kitchen", "heating", 21.8, 22.0)
  private val upstairs = climate("climate.upstairs", "Upstairs", "idle", 22.8, 22.7)
  private val status = EntityState("sensor.status", "Online")

  private val snapshot =
    HaSnapshot(states = listOf(kitchen, upstairs, status).associateBy { it.entityId })

  private fun climateTile(entity: String, name: String, position: String? = null) =
    CardConfig(
      type = "tile",
      raw =
        buildJsonObject {
          put("type", "tile")
          put("entity", entity)
          put("name", name)
          put("icon", "mdi:thermostat")
          position?.let { put("features_position", it) }
          putJsonArray("features") {
            addJsonObject { put("type", "target-temperature") }
            addJsonObject {
              put("type", "climate-hvac-modes")
              put("style", "dropdown")
            }
          }
        },
    )

  private val colouredTile =
    CardConfig(
      type = "tile",
      raw =
        buildJsonObject {
          put("type", "tile")
          put("entity", "sensor.status")
          put("name", "Status")
          put("icon", "mdi:chip")
          put("color", "blue")
        },
    )

  private fun documentText(card: CardConfig): String = runBlocking {
    val doc =
      captureCardDocument(
        context = org.robolectric.RuntimeEnvironment.getApplication(),
        widthPx = 720,
        heightPx = 400,
        registry = registry,
        card = card,
        snapshot = snapshot,
        forceRefresh = true,
      )
    String(doc.bytes, Charsets.UTF_8)
  }

  @Test
  fun theClimateTileCarriesStateTargetAndModes() {
    val text = documentText(climateTile("climate.kids_room", "Kitchen"))
    assertTrue("Heat · 21.8 °C" in text, "the secondary line: mode and current temperature")
    assertTrue("22.0 °C" in text, "the target temperature")
    assertTrue("Off" in text && "Heat" in text, "the hvac mode buttons")
  }

  @Test fun rendersClimateTilesLight() = renderAll(dark = false)

  @Test fun rendersClimateTilesDark() = renderAll(dark = true)

  private fun renderAll(dark: Boolean) {
    val suffix = if (dark) "dark" else "light"
    val cards =
      listOf(
        "kitchen-heating-$suffix" to climateTile("climate.kids_room", "Kitchen"),
        "upstairs-idle-$suffix" to climateTile("climate.upstairs", "Upstairs"),
        "kitchen-inline-$suffix" to
          climateTile("climate.kids_room", "Kitchen", position = "inline"),
        "status-blue-$suffix" to colouredTile,
      )
    val theme = haThemeFor(ThemeStyle.TerrazzoHome, dark)
    compose.setContent {
      Column(Modifier.background(if (dark) Color.Black else Color.White).padding(8.dp)) {
        for ((name, card) in cards) {
          Box(Modifier.width(360.dp).padding(vertical = 4.dp).testTag(name)) {
            CachedCardPreview(
              cacheKey = "tile-$name",
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
      }
    }
    compose.waitForIdle()
    val colours = cards.associate { (name, _) -> name to capture(name) }
    assertTrue(colours.getValue("kitchen-heating-$suffix").size > 2, "the tile draws content")
    // The configured blue (#2196F3) tints the icon chip.
    assertTrue(
      colours.getValue("status-blue-$suffix").any {
        it.red < 0.3f && it.blue > 0.8f && it.green in 0.45f..0.75f
      },
      "the colour: blue tile draws HA's blue",
    )
  }

  /**
   * As a launcher widget the features sit behind one size gate: the roomy branch carries them, the
   * compact sibling doesn't, and the document registers the tile's on/off binding once (a named
   * boolean in two state-layout branches blanks one of them, #224).
   */
  @Test
  fun widgetsGateTheFeaturesOnSize() {
    val bytes = directWidget(climateTile("climate.kids_room", "Kitchen"), 200, 200)
    assertTrue("22.0 °C" in String(bytes, Charsets.UTF_8), "the roomy branch has the features")
    val names = RcDocumentCodec.decode(bytes, RcWireLimits()).namedVariables.map { it.name }
    assertTrue(
      names.count { it == "climate.kids_room.is_on" } <= 1,
      "one live tile per widget document: $names",
    )
  }

  private fun directWidget(card: CardConfig, widthDp: Int, heightDp: Int): ByteArray = runBlocking {
    val density = 420
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

  private fun capture(name: String): Set<Color> {
    val image = compose.onNodeWithTag(name).captureToImage()
    val dir = File("build/tile-captures").also { it.mkdirs() }
    dir.resolve("$name.png").outputStream().use {
      image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
    val pixels = image.toPixelMap()
    val colors = mutableSetOf<Color>()
    for (y in 0 until pixels.height) for (x in 0 until pixels.width) colors += pixels[x, y]
    return colors
  }
}
