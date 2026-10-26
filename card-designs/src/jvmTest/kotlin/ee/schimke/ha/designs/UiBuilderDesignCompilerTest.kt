package ee.schimke.ha.designs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignSurface
import ee.schimke.ha.rc.CardDesignTemplates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface

/**
 * A hand-made card from UI Builder: a picture of the open garage, and the door's picture over the
 * doorway, its height following the cover's `is_on` (open hides it up under the lintel). Compiled
 * here, it binds the card's own value by name, and the door moves when the host pushes it.
 */
class UiBuilderDesignCompilerTest {
  private val compiler = UiBuilderDesignCompiler()

  private val key =
    DesignKey(
      cardType = "tile",
      surface = DesignSurface.MobileApp,
      rcApiLevel = CardDesignTemplates.MOBILE_APP_API_LEVEL,
      shape = "device_class=garage;domain=cover;state_icon=garage",
      theme = "baked:Material3:light",
    )

  /** A solid [color] picture, [width] × [height]: stand-ins for photos. */
  private fun picture(color: Int, width: Int = 64, height: Int = 48): ByteArray {
    val surface = Surface.makeRasterN32Premul(width, height)
    surface.canvas.clear(color)
    return requireNotNull(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)).bytes
  }

  private val assets =
    mapOf("garage-open" to picture(Color.GREEN), "garage-door" to picture(Color.RED))

  private val document =
    Json.parseToJsonElement(
        """
        {
          "schema": "compose-ui-builder-document/v1-candidate",
          "id": "garage", "title": "Garage door", "revision": 5,
          "catalogPin": {"systemId": "ha-cards", "catalogRevision": "candidate",
            "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"},
          "environment": {"widthDp": 360, "heightDp": 300, "density": 2, "theme": "light",
            "locale": "en-GB", "fontScale": 1, "layoutDirection": "ltr"},
          "stateVariables": {
            "cover.s0.is_on": {"type": "value", "valueType": "bool", "nullable": false,
              "initialValue": false, "persistence": "preview"}
          },
          "roots": ["stage"],
          "nodes": {
            "stage": {"id": "stage", "componentId": "layout/box", "properties": {},
              "modifiers": [{"type": "size", "widthDp": 360, "heightDp": 300}],
              "slots": {"children": ["inside", "door-well"]}},
            "inside": {"id": "inside", "componentId": "asset/image",
              "properties": {
                "assetKey": {"type": "string", "value": "garage-open"},
                "contentDescription": {"type": "string", "value": "Garage door"},
                "contentScale": {"type": "string", "value": "fillBounds"}},
              "modifiers": [{"type": "fillMaxSize"}], "slots": {}},
            "door-well": {"id": "door-well", "componentId": "layout/box",
              "properties": {"contentAlignment": {"type": "string", "value": "topCenter"}},
              "modifiers": [{"type": "offset", "xDp": 40, "yDp": 80},
                {"type": "size", "widthDp": 280, "heightDp": 200}],
              "slots": {"children": ["door"]}},
            "door": {"id": "door", "componentId": "asset/image",
              "properties": {
                "assetKey": {"type": "string", "value": "garage-door"},
                "contentScale": {"type": "string", "value": "fillBounds"}},
              "modifiers": [{"type": "fillMaxWidth"},
                {"type": "height", "heightDp": {"type": "expr", "op": "select", "args": [
                  {"type": "state", "variable": "cover.s0.is_on"},
                  {"type": "float", "value": 3}, {"type": "float", "value": 200}]}}],
              "slots": {}}
          }
        }
        """
      )
      .jsonObject

  /** [bytes] played at 360dp wide with [values] pushed, as ARGB pixels. */
  private fun play(bytes: ByteArray, values: Map<String, RcNamedValue>): Bitmap {
    val density = 1f
    val image =
      ImageComposeScene(360, 300, Density(density)) {
          Box(Modifier.width(360.dp)) {
            RcComposePlayer(
              bytes,
              namedValues = mutableStateMapOf<String, RcNamedValue>().apply { putAll(values) },
            )
          }
        }
        .use { scene ->
          scene.render()
          scene.render(2_000_000_000L)
        }
    return Bitmap.makeFromImage(image)
  }

  private fun Bitmap.isRed(x: Int, y: Int): Boolean {
    val pixel = getColor(x, y)
    return Color.getR(pixel) > 200 && Color.getG(pixel) < 60
  }

  @Test
  fun theDoorBindsTheCoversOpenStateByName() {
    val bytes = compiler.compile(document, assets, key)
    val names =
      RcDocumentCodec.decode(bytes, RcWireLimits())
        .operations
        .filterIsInstance<RcNamedVariable>()
        .map { it.name }
    assertTrue("USER:cover.s0.is_on" in names, "named values: $names")
  }

  @Test
  fun theDoorFollowsTheCover() {
    val bytes = compiler.compile(document, assets, key)
    // Halfway down the doorway: the door's picture when closed, the garage's when open.
    val closed = play(bytes, mapOf("cover.s0.is_on" to RcNamedValue.Integer(0)))
    val open = play(bytes, mapOf("cover.s0.is_on" to RcNamedValue.Integer(1)))
    assertTrue(closed.isRed(180, 200), "closed: the door covers the doorway")
    assertTrue(!open.isRed(180, 200), "open: the doorway shows the garage")
  }

  @Test
  fun whatIsntDrawnHereIsRefusedByNode() {
    val unknown =
      JsonObject(
        document +
          ("nodes" to
            JsonObject(
              document.getValue("nodes").jsonObject +
                ("stage" to
                  Json.parseToJsonElement(
                    """{"id": "stage", "componentId": "m3/button", "slots": {}}"""
                  ))
            ))
      )
    val problems = compiler.problems(unknown, assets.keys)
    assertEquals(listOf("stage: `m3/button` isn't drawn here yet"), problems.take(1))
    assertTrue(compiler.problems(document, setOf("garage-open")).any { "garage-door" in it })
    assertFailsWith<IllegalArgumentException> { compiler.compile(unknown, assets, key) }
  }
}
