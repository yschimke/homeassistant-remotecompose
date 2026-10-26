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
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.fillMaxHeight
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.graphicsLayer
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
 * design's own pictures (`asset/image`) and text, with size, offset, padding, alpha, scale and
 * rotation.
 *
 * A property or modifier value may read a state variable (`{"type": "state"}`), or pick between two
 * numbers by a boolean one (`{"type": "expr", "op": "select"}`). State variables become named
 * values in the `USER` domain, so a variable named for a card binding (`cover.s0.is_on`) is the
 * value the app pushes, and the card follows Home Assistant without a re-encode. A transform that
 * reads state (an offset, a scale, a rotation, alpha) tweens when it changes, never on the first
 * frame (the host seeds it first); a size that reads state snaps.
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
    val design =
      Design(document, assets.mapValues { (_, bytes) -> decode(bytes) }, dark = isDark(key))
    val problems = design.problems(assets.keys)
    require(problems.isEmpty()) { problems.joinToString("; ") }
    return CardDesignTemplates.captureWith(host, key) { card, snapshot, modifier ->
        FramedCard(card, snapshot, modifier) { design.Render(it) }
      }
      .bytes
  }

  private fun decode(bytes: ByteArray): ImageBitmap =
    Image.makeFromEncoded(bytes).toComposeImageBitmap()

  private class Design(
    document: JsonObject,
    private val images: Map<String, ImageBitmap>,
    /** Whether the theme it is captured for is dark: what [NIGHT] resolves by. */
    private val dark: Boolean = false,
  ) {
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
          val type = (value as? JsonObject)?.string("type")
          when {
            // Only a text's text is drawn live; any other property is captured as it is.
            type in DYNAMIC && !(component == TEXT && name == "text") ->
              add("$id.$name: a property reading state isn't drawn live here yet")
            type == "colorToken" && name != "color" ->
              add("$id.$name: a colour token is only drawn for a colour")
            else -> valueProblem(value)?.let { add("$id.$name: $it") }
          }
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
        "colorToken" ->
          obj
            .string("value")
            .takeIf { it !in COLOR_TOKENS }
            ?.let { "colour token `$it` isn't one of ${COLOR_TOKENS.joinToString()}" }
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
            color = color(properties["color"]) ?: theme.primaryText,
            fontSize =
              literal(properties["fontSize"])?.floatOrNull?.roundToInt()?.rsp ?: HaType.Body,
            maxLines = literal(properties["maxLines"])?.intOrNull ?: Int.MAX_VALUE,
          )
        }
        // A card's own surface: the app draws the card's frame, so it is only its content.
        else -> children(id).forEach { Node(it, modifier) }
      }
    }

    /**
     * [modifiers] after [start]. A size reading state snaps to its new value: the player measures
     * when a value changes, so a tween there would be measured at its old value. A state-driven
     * offset, a scale, a rotation and alpha are drawn instead, which tween: together one
     * `graphicsLayer` (a player takes one per element) where the first of them is, so what follows
     * it (a background, the content) is drawn moved, scaled and faded. Translations add, scales and
     * alphas multiply, rotations add. A layer draws its translation unscaled, as an offset before a
     * scale does.
     */
    @Composable
    private fun applyModifiers(start: RemoteModifier, modifiers: JsonArray?): RemoteModifier {
      val all = modifiers.orEmpty().map { it.jsonObject }
      var translationX: RemoteFloat? = null
      var translationY: RemoteFloat? = null
      var scaleX: RemoteFloat? = null
      var scaleY: RemoteFloat? = null
      var rotation: RemoteFloat? = null
      var alpha: RemoteFloat? = null
      fun RemoteFloat?.plus(other: RemoteFloat) = this?.let { it + other } ?: other
      fun RemoteFloat?.times(other: RemoteFloat) = this?.let { it * other } ?: other
      fun isLayer(m: JsonObject): Boolean =
        when (m.string("type")) {
          "scale",
          "rotate",
          "alpha" -> true
          "offset" ->
            number(m["xDp"])?.constantValueOrNull == null ||
              number(m["yDp"])?.constantValueOrNull == null
          else -> false
        }
      all.filter(::isLayer).forEach { m ->
        when (m.string("type")) {
          "offset" -> {
            translationX =
              translationX.plus(animated(number(m["xDp"]) ?: 0f.rf).asRemoteDp().toPx())
            translationY =
              translationY.plus(animated(number(m["yDp"]) ?: 0f.rf).asRemoteDp().toPx())
          }
          "scale" -> {
            scaleX = scaleX.times(animated(number(m["scaleX"]) ?: 1f.rf))
            scaleY = scaleY.times(animated(number(m["scaleY"]) ?: 1f.rf))
          }
          "rotate" -> rotation = rotation.plus(animated(number(m["degrees"]) ?: 0f.rf))
          "alpha" -> alpha = alpha.times(animated(number(m["alpha"]) ?: 1f.rf))
        }
      }
      var applied = start
      var layered = false
      all.forEach { m ->
        if (isLayer(m)) {
          if (!layered) {
            layered = true
            applied = applied.graphicsLayer {
              translationX?.let { this.translationX = it }
              translationY?.let { this.translationY = it }
              scaleX?.let { this.scaleX = it }
              scaleY?.let { this.scaleY = it }
              rotation?.let { rotationZ = it }
              alpha?.let { this.alpha = it }
            }
          }
          return@forEach
        }
        applied =
          when (m.string("type")) {
            "fillMaxWidth" -> applied.fillMaxWidth()
            "fillMaxHeight" -> applied.fillMaxHeight()
            "fillMaxSize" -> applied.fillMaxSize()
            "size" ->
              applied.size(size(m["widthDp"]).asRemoteDp(), size(m["heightDp"]).asRemoteDp())
            "width" -> applied.width(size(m["widthDp"]).asRemoteDp())
            "height" -> applied.height(size(m["heightDp"]).asRemoteDp())
            "offset" -> applied.offset(size(m["xDp"]).asRemoteDp(), size(m["yDp"]).asRemoteDp())
            "padding" ->
              applied.padding(
                start = size(m["startDp"]).asRemoteDp(),
                top = size(m["topDp"]).asRemoteDp(),
                end = size(m["endDp"]).asRemoteDp(),
                bottom = size(m["bottomDp"]).asRemoteDp(),
              )
            "background" -> color(m["color"])?.let { applied.background(it) } ?: applied
            else -> applied
          }
      }
      return applied
    }

    /** A layout number: a literal, a state variable or a select, applied as it changes. */
    private fun size(value: JsonElement?): RemoteFloat = number(value) ?: 0f.rf

    /** [number], tweened when it changes; a constant as it is. */
    @Composable
    private fun animated(number: RemoteFloat): RemoteFloat =
      if (number.constantValueOrNull != null) number else animateRemoteFloat(number)

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

    /**
     * A colour value: a literal (`#RRGGBB`, `#AARRGGBB`), or a theme role (`colorToken`) resolved
     * in the theme the design is captured for, so each published theme draws its own: light, dark
     * and every palette. [NIGHT] is black in a dark theme and clear in a light one, for a scrim
     * that only darkens at night.
     */
    @Composable
    private fun color(value: JsonElement?): RemoteColor? {
      val obj = value as? JsonObject
      val name = (obj?.get("value") as? JsonPrimitive)?.contentOrNull ?: return null
      if (obj.string("type") != "colorToken") return hexColor(name)
      val theme = currentRemoteHaTheme()
      return when (name) {
        NIGHT -> Color(if (dark) 0xFF000000.toInt() else 0x00000000).rc
        ACCENT,
        "placeholderAccent" -> theme.placeholderAccent
        "cardBackground" -> theme.cardBackground
        "dashboardBackground" -> theme.dashboardBackground
        "sectionBackground" -> theme.sectionBackground
        "primaryText" -> theme.primaryText
        "secondaryText" -> theme.secondaryText
        "linkText" -> theme.linkText
        "divider" -> theme.divider
        "placeholderBackground" -> theme.placeholderBackground
        "unknownAccent" -> theme.unknownAccent
        else -> null
      }
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
        "scale",
        "rotate",
        "background",
      )

    private val LITERALS = setOf("string", "bool", "int", "float", "dimension", "color", "null")

    /** Values that read state: drawn live in modifiers and a text's text, refused elsewhere. */
    private val DYNAMIC = setOf("state", "expr")

    /** Black in a dark theme, clear in a light one: a scrim for night. */
    const val NIGHT = "night"

    /** The palette's hero colour (`placeholderAccent`). */
    const val ACCENT = "accent"

    /** The colour tokens a design may name: the card theme's roles, [ACCENT] and [NIGHT]. */
    val COLOR_TOKENS =
      setOf(
        NIGHT,
        ACCENT,
        "cardBackground",
        "dashboardBackground",
        "sectionBackground",
        "primaryText",
        "secondaryText",
        "linkText",
        "divider",
        "placeholderAccent",
        "placeholderBackground",
        "unknownAccent",
      )

    /** Whether [key]'s theme is a dark one: `baked:<palette>:dark` or `style:<id>:dark`. */
    fun isDark(key: DesignKey): Boolean = key.theme.split(':').getOrNull(2) == "dark"

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
    private fun hexColor(hex: String): RemoteColor? {
      val digits = hex.removePrefix("#")
      val value = digits.toLongOrNull(16) ?: return null
      val argb = if (digits.length <= 6) value or 0xFF000000 else value
      return Color(argb.toInt()).rc
    }
  }
}
