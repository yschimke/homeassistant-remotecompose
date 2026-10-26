@file:Suppress("RestrictedApi")

package ee.schimke.ha.designs

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteImage
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.alpha
import androidx.compose.remote.creation.compose.modifier.fillMaxHeight
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.offset
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.modifier.width
import androidx.compose.remote.creation.compose.state.RemoteBoolean
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.asRemoteDp
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.CommonCaptureHost
import ee.schimke.ha.rc.FramedCard
import ee.schimke.ha.rc.components.HaType
import ee.schimke.ha.rc.components.animateRemoteFloat
import ee.schimke.ha.rc.components.currentRemoteHaTheme
import ee.schimke.ha.rc.components.rememberLocalNamedRemoteBitmap
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.skia.Image

/**
 * Compiles a person's UI Builder design (`ha-cards`) to a Remote Compose template here, for when UI
 * Builder can't export one (`export.rc` is a build feature of the builder, and the hosted one
 * doesn't serve it). It covers what a hand-made card is drawn with: boxes, columns and rows, the
 * design's own pictures (`asset/image`) and text, with size, offset, padding and alpha.
 *
 * A property or modifier value may read a state variable (`{"type": "state"}`), or pick between two
 * numbers by a boolean one (`{"type": "expr", "op": "select"}`). State variables become named
 * values in the `USER` domain, so a variable named for a card binding (`cover.s0.is_on`) is the
 * value the app pushes, and the card follows Home Assistant without a re-encode. A number that
 * reads state tweens when it changes, never on the first frame (the host seeds it first).
 *
 * Anything else is refused by [problems], naming the node, rather than drawn approximately.
 */
class UiBuilderDesignCompiler(
  private val host: CommonCaptureHost = CommonCaptureHost(LibraryCaptureProfile.OPERATIONS)
) {
  /** Every reason [document] can't be compiled here, by node; empty when it can. */
  fun problems(document: JsonObject, assets: Set<String>): List<String> =
    Design(document, emptyMap()).problems(assets)

  /**
   * [document] as a template for [key] (the library design it varies), drawing [assets] (an asset
   * key's image bytes). Throws [IllegalArgumentException] naming each problem when it can't. It
   * composes and captures on the calling thread: a server runs one at a time.
   */
  fun compile(
    document: JsonObject,
    assets: Map<String, ByteArray>,
    key: DesignKey,
  ): ByteArray {
    val design = Design(document, assets.mapValues { (_, bytes) -> decode(bytes) })
    val problems = design.problems(assets.keys)
    require(problems.isEmpty()) { problems.joinToString("; ") }
    return CardDesignTemplates.captureWith(host, key) { card, snapshot, modifier ->
        FramedCard(card, snapshot, modifier) { design.Render(it) }
      }
      .bytes
  }

  private fun decode(bytes: ByteArray): ImageBitmap =
    Image.makeFromEncoded(bytes).toComposeImageBitmap()

  private class Design(document: JsonObject, private val images: Map<String, ImageBitmap>) {
    private val nodes: Map<String, JsonObject> =
      document["nodes"]?.jsonObject.orEmpty().mapValues { it.value.jsonObject }
    private val roots: List<String> =
      document["roots"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
    private val variables: Map<String, JsonObject> =
      document["stateVariables"]?.jsonObject.orEmpty().mapValues { it.value.jsonObject }
    private val catalog: String? =
      document["catalogPin"]?.jsonObject?.get("systemId")?.jsonPrimitive?.contentOrNull

    fun problems(assets: Set<String>): List<String> = buildList {
      if (catalog != CATALOG) add("its catalog is `$catalog`, not `$CATALOG`")
      if (roots.size != 1) add("a card design has one root, not ${roots.size}")
      roots.filter { it !in nodes }.forEach { add("root $it is not a node") }
      nodes.forEach { (id, node) ->
        val component = node.string("componentId")
        if (component !in COMPONENTS) add("$id: `$component` isn't drawn here yet")
        (node["modifiers"] as? JsonArray).orEmpty().forEach { modifier ->
          val type = (modifier as? JsonObject)?.string("type")
          if (type !in MODIFIERS) add("$id: modifier `$type` isn't drawn here yet")
          (modifier as? JsonObject)?.forEach { (field, value) ->
            if (field != "type") valueProblem(value)?.let { add("$id.$type.$field: $it") }
          }
        }
        node["properties"]?.jsonObject?.forEach { (name, value) ->
          valueProblem(value)?.let { add("$id.$name: $it") }
        }
        if (component == IMAGE) {
          val key = literal(node.properties()["assetKey"])?.contentOrNull
          if (key == null || key !in assets) add("$id: no picture for asset `$key`")
        }
        node["eventBindings"]
          ?.jsonObject
          ?.takeIf { it.isNotEmpty() }
          ?.let { add("$id: events (${it.keys.joinToString()}) aren't drawn here yet") }
        node["predicate"]
          ?.takeIf { it !is kotlinx.serialization.json.JsonNull }
          ?.let { add("$id: a predicate isn't drawn here yet") }
        children(id).filter { it !in nodes }.forEach { add("$id: missing child $it") }
      }
    }

    private fun valueProblem(value: JsonElement): String? {
      val obj = value as? JsonObject ?: return null
      return when (val type = obj.string("type")) {
        "state" -> obj.string("variable").takeIf { it !in variables }?.let { "no state `$it`" }
        "expr" -> {
          val args = obj["args"]?.jsonArray.orEmpty()
          when {
            obj.string("op") != "select" -> "expression `${obj.string("op")}` isn't drawn here yet"
            args.size != 3 -> "select takes a condition and two values"
            else -> args.firstNotNullOfOrNull(::valueProblem)
          }
        }
        in LITERALS -> null
        else -> "a `$type` value isn't drawn here yet"
      }
    }

    @Composable
    fun Render(modifier: RemoteModifier) {
      Node(roots.single(), modifier)
    }

    @Composable
    private fun Node(id: String, outer: RemoteModifier) {
      val node = nodes.getValue(id)
      val modifier = applyModifiers(outer, node["modifiers"] as? JsonArray)
      val properties = node.properties()
      when (node.string("componentId")) {
        BOX ->
          RemoteBox(modifier = modifier, contentAlignment = alignment(properties)) {
            children(id).forEach { Node(it, RemoteModifier) }
          }
        COLUMN ->
          RemoteColumn(
            modifier = modifier,
            verticalArrangement = verticalArrangement(properties),
            horizontalAlignment = horizontalAlignment(properties),
          ) {
            children(id).forEach { Node(it, RemoteModifier) }
          }
        ROW ->
          RemoteRow(
            modifier = modifier,
            horizontalArrangement = horizontalArrangement(properties),
            verticalAlignment = verticalAlignment(properties),
          ) {
            children(id).forEach { Node(it, RemoteModifier) }
          }
        IMAGE -> {
          val key = literal(properties["assetKey"])!!.content
          // Baked in, under a name of the design's own (no dot): no card binding reads it.
          val bitmap = rememberLocalNamedRemoteBitmap(assetName(key)) { images.getValue(key) }
          RemoteImage(
            remoteBitmap = bitmap,
            contentDescription = literal(properties["contentDescription"])?.contentOrNull?.rs,
            modifier = modifier,
            contentScale = contentScale(literal(properties["contentScale"])?.contentOrNull),
          )
        }
        TEXT -> {
          val theme = currentRemoteHaTheme()
          RemoteText(
            text = string(properties["text"]) ?: "".rs,
            modifier = modifier,
            color = literal(properties["color"])?.contentOrNull?.let(::color) ?: theme.primaryText,
            fontSize =
              literal(properties["fontSize"])?.floatOrNull?.roundToInt()?.rsp ?: HaType.Body,
            maxLines = literal(properties["maxLines"])?.intOrNull ?: Int.MAX_VALUE,
          )
        }
        // A card's own surface: the app draws the card's frame, so it is only its content.
        else -> children(id).forEach { Node(it, modifier) }
      }
    }

    @Composable
    private fun applyModifiers(start: RemoteModifier, modifiers: JsonArray?): RemoteModifier {
      var applied = start
      modifiers.orEmpty().forEach {
        val m = it.jsonObject
        applied =
          when (m.string("type")) {
            "fillMaxWidth" -> applied.fillMaxWidth()
            "fillMaxHeight" -> applied.fillMaxHeight()
            "fillMaxSize" -> applied.fillMaxSize()
            "size" -> applied.size(dp(m["widthDp"]).asRemoteDp(), dp(m["heightDp"]).asRemoteDp())
            "width" -> applied.width(dp(m["widthDp"]).asRemoteDp())
            "height" -> applied.height(dp(m["heightDp"]).asRemoteDp())
            "offset" -> applied.offset(dp(m["xDp"]).asRemoteDp(), dp(m["yDp"]).asRemoteDp())
            "padding" ->
              applied.padding(
                start = dp(m["startDp"]).asRemoteDp(),
                top = dp(m["topDp"]).asRemoteDp(),
                end = dp(m["endDp"]).asRemoteDp(),
                bottom = dp(m["bottomDp"]).asRemoteDp(),
              )
            "alpha" -> applied.alpha(dp(m["alpha"], default = 1f))
            else -> applied
          }
      }
      return applied
    }

    /** A number: a literal, a state variable, or a select; tweened on change when it can change. */
    @Composable
    private fun dp(value: JsonElement?, default: Float = 0f): RemoteFloat {
      val number = number(value) ?: return default.rf
      return if (number.constantValueOrNull != null) number else animateRemoteFloat(number)
    }

    private fun number(value: JsonElement?): RemoteFloat? {
      if (value == null || value is kotlinx.serialization.json.JsonNull) return null
      if (value is JsonPrimitive) return value.floatOrNull?.rf
      val obj = value.jsonObject
      return when (obj.string("type")) {
        "state" -> namedFloat(obj.string("variable")!!)
        "expr" -> {
          val (condition, ifTrue, ifFalse) = obj.getValue("args").jsonArray
          boolean(condition)?.select(number(ifTrue) ?: 0f.rf, number(ifFalse) ?: 0f.rf)
        }
        else -> literal(obj)?.floatOrNull?.rf
      }
    }

    private fun boolean(value: JsonElement): RemoteBoolean? {
      if (value is JsonPrimitive) return value.booleanOrNull?.let { RemoteBoolean(it) }
      val obj = value.jsonObject
      return when (obj.string("type")) {
        "state" -> {
          val name = obj.string("variable")!!
          val initial = variables[name]?.get("initialValue")?.jsonPrimitive?.booleanOrNull ?: false
          RemoteBoolean.createNamedRemoteBoolean(name, initial, RemoteState.Domain.User)
        }
        else -> literal(obj)?.booleanOrNull?.let { RemoteBoolean(it) }
      }
    }

    private fun namedFloat(name: String): RemoteFloat {
      val declaration = variables.getValue(name)
      val initial = declaration["initialValue"]?.jsonPrimitive
      return when (declaration["valueType"]?.jsonPrimitive?.contentOrNull) {
        "bool" ->
          RemoteBoolean.createNamedRemoteBoolean(
              name,
              initial?.booleanOrNull ?: false,
              RemoteState.Domain.User,
            )
            .select(1f.rf, 0f.rf)
        else ->
          RemoteFloat.createNamedRemoteFloat(
            name,
            initial?.floatOrNull ?: 0f,
            RemoteState.Domain.User,
          )
      }
    }

    private fun string(value: JsonElement?): RemoteString? {
      val obj = value as? JsonObject ?: return null
      if (obj.string("type") != "state") return literal(obj)?.contentOrNull?.rs
      val name = obj.string("variable")!!
      val initial = variables[name]?.get("initialValue")?.jsonPrimitive?.contentOrNull.orEmpty()
      return RemoteString.createNamedRemoteString(name, initial, RemoteState.Domain.User)
    }

    private fun children(id: String): List<String> =
      nodes[id]?.get("slots")?.jsonObject.orEmpty().values.flatMap { slot ->
        (slot as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
      }
  }

  companion object {
    const val CATALOG = "ha-cards"
    private const val BOX = "layout/box"
    private const val COLUMN = "layout/column"
    private const val ROW = "layout/row"
    private const val IMAGE = "asset/image"
    private const val TEXT = "ha-cards/remote-text"

    private val COMPONENTS =
      setOf(
        BOX,
        COLUMN,
        ROW,
        IMAGE,
        TEXT,
        "ha-cards/dashboard-card",
        "ha-cards/dashboard-section",
        "ha-cards/remote-card",
      )

    private val MODIFIERS =
      setOf(
        "fillMaxWidth",
        "fillMaxHeight",
        "fillMaxSize",
        "size",
        "width",
        "height",
        "offset",
        "padding",
        "alpha",
      )

    private val LITERALS = setOf("string", "bool", "int", "float", "dimension", "color", "null")

    /** The named bitmap an asset is baked in as: `asset_<key>`, dots and dashes as underscores. */
    fun assetName(key: String): String = "asset_" + key.replace(Regex("[^A-Za-z0-9_]"), "_")

    private fun JsonObject.string(name: String): String? =
      (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.properties(): Map<String, JsonElement> =
      this["properties"]?.jsonObject.orEmpty()

    private fun literal(value: JsonElement?): JsonPrimitive? =
      (value as? JsonObject)?.get("value") as? JsonPrimitive

    private fun alignment(properties: Map<String, JsonElement>): RemoteAlignment =
      when (literal(properties["contentAlignment"])?.contentOrNull) {
        "topCenter" -> RemoteAlignment.TopCenter
        "topEnd" -> RemoteAlignment.TopEnd
        "centerStart" -> RemoteAlignment.CenterStart
        "center" -> RemoteAlignment.Center
        "centerEnd" -> RemoteAlignment.CenterEnd
        "bottomStart" -> RemoteAlignment.BottomStart
        "bottomCenter" -> RemoteAlignment.BottomCenter
        "bottomEnd" -> RemoteAlignment.BottomEnd
        else -> RemoteAlignment.TopStart
      }

    private fun verticalArrangement(properties: Map<String, JsonElement>) =
      when (literal(properties["verticalArrangement"])?.contentOrNull) {
        "center" -> RemoteArrangement.Center
        "bottom" -> RemoteArrangement.Bottom
        "spaceBetween" -> RemoteArrangement.SpaceBetween
        "spaceAround" -> RemoteArrangement.SpaceAround
        "spaceEvenly" -> RemoteArrangement.SpaceEvenly
        else ->
          literal(properties["verticalSpacingDp"])?.floatOrNull?.let {
            RemoteArrangement.spacedBy(it.rdp)
          } ?: RemoteArrangement.Top
      }

    private fun horizontalArrangement(properties: Map<String, JsonElement>) =
      when (literal(properties["horizontalArrangement"])?.contentOrNull) {
        "center" -> RemoteArrangement.Center
        "end" -> RemoteArrangement.End
        "spaceBetween" -> RemoteArrangement.SpaceBetween
        "spaceAround" -> RemoteArrangement.SpaceAround
        "spaceEvenly" -> RemoteArrangement.SpaceEvenly
        else ->
          literal(properties["horizontalSpacingDp"])?.floatOrNull?.let {
            RemoteArrangement.spacedBy(it.rdp)
          } ?: RemoteArrangement.Start
      }

    private fun horizontalAlignment(properties: Map<String, JsonElement>) =
      when (literal(properties["horizontalAlignment"])?.contentOrNull) {
        "center" -> RemoteAlignment.CenterHorizontally
        "end" -> RemoteAlignment.End
        else -> RemoteAlignment.Start
      }

    private fun verticalAlignment(properties: Map<String, JsonElement>) =
      when (literal(properties["verticalAlignment"])?.contentOrNull) {
        "center" -> RemoteAlignment.CenterVertically
        "bottom" -> RemoteAlignment.Bottom
        else -> RemoteAlignment.Top
      }

    private fun contentScale(name: String?): ContentScale =
      when (name) {
        "crop" -> ContentScale.Crop
        "fillBounds" -> ContentScale.FillBounds
        "inside" -> ContentScale.Inside
        else -> ContentScale.Fit
      }

    /** `#RRGGBB` or `#AARRGGBB`. */
    private fun color(hex: String): RemoteColor? {
      val digits = hex.removePrefix("#")
      val value = digits.toLongOrNull(16) ?: return null
      val argb = if (digits.length <= 6) value or 0xFF000000 else value
      return Color(argb.toInt()).rc
    }
  }
}
