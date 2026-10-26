package ee.schimke.ha.designs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface

/**
 * A hand-made card from UI Builder: a picture of the open garage, and one picture of the door over
 * the doorway, scaled from its top edge by the cover's `is_on` (open folds it up under the lintel).
 * Compiled here, it binds the card's own value by name, and the door moves when the host pushes it.
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
              "modifiers": [{"type": "fillMaxSize"},
                {"type": "offset", "xDp": 0, "yDp": {"type": "expr", "op": "select", "args": [
                  {"type": "state", "variable": "cover.s0.is_on"},
                  {"type": "float", "value": -98.5}, {"type": "float", "value": 0}]}},
                {"type": "scale", "scaleX": 1, "scaleY": {"type": "expr", "op": "select",
                  "args": [{"type": "state", "variable": "cover.s0.is_on"},
                  {"type": "float", "value": 0.015}, {"type": "float", "value": 1}]}}],
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
  fun theDoorMovesOnTheFirstChangeTheHostPushes() {
    val bytes = compiler.compile(document, assets, key)
    var open by mutableStateOf(0)
    // As RemoteComposeCard pushes values: into its own map, as it composes.
    val scene =
      ImageComposeScene(360, 300, Density(1f)) {
        val values = remember {
          mutableStateMapOf<String, RcNamedValue>("cover.s0.is_on" to RcNamedValue.Integer(0))
        }
        val current = open
        SideEffect { values["cover.s0.is_on"] = RcNamedValue.Integer(current) }
        Box(Modifier.width(360.dp)) { RcComposePlayer(bytes, namedValues = values) }
      }
    scene.use { player ->
      var frameNanos = 0L
      fun settle(): Bitmap {
        // Frames a second apart on the frame clock; the player tweens on the wall clock.
        var image = player.render(frameNanos)
        repeat(40) {
          Thread.sleep(20)
          frameNanos += 20_000_000L
          image = player.render(frameNanos)
        }
        return Bitmap.makeFromImage(image)
      }
      assertTrue(settle().isRed(180, 200), "closed")
      open = 1
      Snapshot.sendApplyNotifications()
      assertTrue(!settle().isRed(180, 200), "open on the first change, not the next")
      open = 0
      Snapshot.sendApplyNotifications()
      assertTrue(settle().isRed(180, 200), "closed again")
    }
  }

  /**
   * [document] with a tint over the door in the palette's accent, moving with it, and a night scrim
   * over everything.
   */
  private val adaptive: JsonObject by lazy {
    val nodes = document.getValue("nodes").jsonObject
    val door = nodes.getValue("door").jsonObject
    val well = nodes.getValue("door-well").jsonObject
    val stage = nodes.getValue("stage").jsonObject
    fun withChildren(node: JsonObject, children: List<String>) =
      JsonObject(
        node +
          ("slots" to
            JsonObject(mapOf("children" to JsonArray(children.map { JsonPrimitive(it) }))))
      )
    val tint =
      Json.parseToJsonElement(
          """
          {"id": "tint", "componentId": "layout/box", "modifiers": ${
            JsonArray(
              door.getValue("modifiers").jsonArray +
                Json.parseToJsonElement(
                    """[{"type": "alpha", "alpha": 0.5},
                    {"type": "background", "color": {"type": "colorToken", "value": "accent"}}]"""
                  )
                  .jsonArray
            )
          }}
          """
        )
        .jsonObject
    val night =
      Json.parseToJsonElement(
          """
          {"id": "night", "componentId": "layout/box", "modifiers": [{"type": "fillMaxSize"},
            {"type": "alpha", "alpha": 0.5},
            {"type": "background", "color": {"type": "colorToken", "value": "night"}}]}
          """
        )
        .jsonObject
    JsonObject(
      document +
        ("nodes" to
          JsonObject(
            nodes +
              mapOf(
                "door-well" to withChildren(well, listOf("door", "tint")),
                "stage" to withChildren(stage, listOf("inside", "door-well", "night")),
                "tint" to tint,
                "night" to night,
              )
          ))
    )
  }

  private fun Bitmap.rgb(x: Int, y: Int): Triple<Int, Int, Int> =
    getColor(x, y).let { Triple(Color.getR(it), Color.getG(it), Color.getB(it)) }

  @Test
  fun coloursFollowTheThemeItIsPublishedIn() {
    val closed = mapOf("cover.s0.is_on" to RcNamedValue.Integer(0))
    val light = play(compiler.compile(adaptive, assets, key), closed)
    val dark =
      play(compiler.compile(adaptive, assets, key.copy(theme = "baked:Material3:dark")), closed)
    // Outside the door: the garage as it is by day, darker at night.
    assertEquals(Triple(0, 255, 0), light.rgb(10, 10))
    assertTrue(dark.rgb(10, 10).second < 160, "night scrim: ${dark.rgb(10, 10)}")
    // The door is tinted, no longer its picture's own red, and differently in each theme.
    assertTrue(light.rgb(180, 200) != Triple(255, 0, 0), "tinted: ${light.rgb(180, 200)}")
    assertTrue(light.rgb(180, 200) != dark.rgb(180, 200))
    // Open, the tint folds away with the door: the doorway shows the garage.
    val open =
      play(
        compiler.compile(adaptive, assets, key),
        mapOf("cover.s0.is_on" to RcNamedValue.Integer(1)),
      )
    assertEquals(Triple(0, 255, 0), open.rgb(180, 200))
  }

  @Test
  fun aPropertyReadingStateIsRefusedUnlessItIsDrawnLive() {
    val text =
      Json.parseToJsonElement(
          """
          {"schema": "compose-ui-builder-document/v1-candidate", "id": "t", "title": "t",
           "revision": 1, "catalogPin": {"systemId": "ha-cards", "catalogRevision": "c",
             "capabilityDigest": "c", "nativeRuntimeId": "c"},
           "stateVariables": {
             "cover.s0.state": {"type": "text", "valueType": "string", "initialValue": "Closed",
               "persistence": "preview"},
             "cover.s0.is_on": {"type": "value", "valueType": "bool", "initialValue": false,
               "persistence": "preview"}},
           "roots": ["text"],
           "nodes": {"text": {"id": "text", "componentId": "ha-cards/remote-text",
             "properties": {
               "text": {"type": "state", "variable": "cover.s0.state"},
               "fontSize": {"type": "expr", "op": "select", "args": [
                 {"type": "state", "variable": "cover.s0.is_on"},
                 {"type": "float", "value": 20}, {"type": "float", "value": 14}]}}}}}
          """
        )
        .jsonObject
    // The text is live; its size would be captured as one value, so it is refused.
    assertEquals(
      listOf("text.fontSize: a property reading state isn't drawn live here yet"),
      compiler.problems(text, emptySet()),
    )
  }

  @Test
  fun anUnknownColourTokenIsRefused() {
    val unknown =
      Json.parseToJsonElement(
          adaptive.toString().replace("\"value\":\"accent\"", "\"value\":\"brandPink\"")
        )
        .jsonObject
    assertTrue(compiler.problems(unknown, assets.keys).any { "brandPink" in it })
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
