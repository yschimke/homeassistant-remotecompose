package ee.schimke.ha.rc.cards

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaLiveBindings
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaPictureElement
import ee.schimke.ha.rc.components.HaPictureElementPosition
import ee.schimke.ha.rc.components.HaPictureElementsData
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.RemoteHaPictureElements
import ee.schimke.ha.rc.defaultTapActionFor
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.icons.HaIconMap
import ee.schimke.ha.rc.parseHaAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `picture-elements` card. Each element in the config has a `type:` — we map state-icon /
 * state-label / service-button / icon into the corresponding [HaPictureElement] variant.
 * `style.top` / `style.left` percentages are parsed into a [HaPictureElementPosition] so the
 * renderer can overlay each element at its configured spot on the placeholder; elements without
 * position metadata fall back to the strip across the bottom.
 */
class PictureElementsCardConverter : CardConverter {
  override val cardType: String = CardTypes.PICTURE_ELEMENTS

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 180

  /**
   * A picture-elements card renders from a card design. Per element: its type, its position (whole
   * dp on the card's canvas) and, for entity elements, the entity's [EntityDesign] properties; a
   * state label's text, an icon's on/off and a button's label are bindings. Every template element
   * taps as `#el-<k>`, and playback runs element k's own configured tap.
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? {
    if (!setOf("type", "image", "elements").containsAll(card.raw.keys)) return null
    val elements =
      (card.raw["elements"] as? JsonArray)?.map { it as? JsonObject ?: return null } ?: return null
    var ok = true
    val properties = DesignProperties.build {
      enum("has_image", if (card.raw["image"] != null) 1 else 0)
      enum("count", elements.size)
      elements.forEachIndexed { k, el ->
        val type = el["type"]?.jsonPrimitive?.content
        val entityId = el["entity"]?.jsonPrimitive?.content
        when (type) {
          "state-icon",
          "icon" ->
            with(EntityDesign) {
              if (!entity("k$k.", entityId, el["icon"]?.jsonPrimitive?.content, snapshot))
                ok = false
            }
          "state-label" ->
            if (entityId == null || entityId !in snapshot.states) ok = false
            else string("k$k.domain", CardDesignSlots.domainOf(entityId))
          "service-button" -> if (entityId != null) ok = false
          else -> ok = false
        }
        string("k$k.type", if (type == "icon") "state-icon" else type)
        parsePositionDp(el)?.let { (left, top) ->
          enum("k$k.left", left)
          enum("k$k.top", top)
        }
      }
    }
    return properties.takeIf { ok }
  }

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? {
    val count = properties["count"]?.toIntOrNull() ?: return null
    val states = LinkedHashMap<String, ee.schimke.ha.model.EntityState>()
    var slot = 0
    val elements =
      (0 until count).map { k ->
        val type = properties["k$k.type"] ?: return null
        buildJsonObject {
          put("type", type)
          when (type) {
            "state-icon" -> {
              val entity = EntityDesign.templateEntity(properties, "k$k.", slot++) ?: return null
              states[entity.entityId] = entity
              put("entity", entity.entityId)
              properties["k$k.${EntityDesign.CARD_ICON}"]?.let { put("icon", it) }
            }
            "state-label" -> {
              val id = CardDesignSlots.slotId(properties["k$k.domain"] ?: return null, slot++)
              states[id] = CardDesignSlots.templateEntity(id, placeholderState = "off")
              put("entity", id)
            }
            "service-button" -> put("title", "Action")
          }
          if (type != "state-label") {
            put(
              "tap_action",
              buildJsonObject {
                put("action", "navigate")
                put("navigation_path", "$ELEMENT_TAP$k")
              },
            )
          }
          val left = properties["k$k.left"]?.toIntOrNull()
          val top = properties["k$k.top"]?.toIntOrNull()
          if (left != null && top != null) {
            put(
              "style",
              buildJsonObject {
                put("left", percentFor(left, CANVAS_W_DP))
                put("top", percentFor(top, CANVAS_H_DP))
              },
            )
          }
        }
      }
    val card =
      CardConfig(
        type = cardType,
        raw =
          buildJsonObject {
            put("type", cardType)
            if (properties["has_image"] == "1") put("image", "image")
            put("elements", JsonArray(elements))
          },
      )
    return DesignTemplateInput(card, HaSnapshot(states = states))
  }

  override fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings =
    CardSnapshotBindings(
      buildMap {
        card.raw["image"]?.jsonPrimitive?.content?.let { put("card.image", it) }
        (card.raw["elements"] as? JsonArray)?.forEachIndexed { k, el ->
          val obj = el as? JsonObject ?: return@forEachIndexed
          if (obj["type"]?.jsonPrimitive?.content == "service-button") {
            put("card.el.$k.label", obj["title"]?.jsonPrimitive?.content ?: "Action")
          }
        }
      },
      emptyMap(),
      emptyMap(),
      emptyMap(),
    )

  /** A template element tap (`#el-<k>`): element k's own configured tap. */
  override fun designAction(
    card: CardConfig,
    snapshot: HaSnapshot?,
    slot: Int,
    templateAction: HaAction,
  ): HaAction? {
    val path = (templateAction as? HaAction.Navigate)?.path ?: return null
    val k = path.removePrefix(ELEMENT_TAP).takeIf { it != path }?.toIntOrNull() ?: return null
    val el = (card.raw["elements"] as? JsonArray)?.getOrNull(k) as? JsonObject ?: return null
    if (el["type"]?.jsonPrimitive?.content == "state-label") return null
    return parseTap(el, el["entity"]?.jsonPrimitive?.content)
  }

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val image = card.raw["image"]?.jsonPrimitive?.content
    val arr = card.raw["elements"] as? JsonArray ?: JsonArray(emptyList())
    val elements = arr.mapNotNull { el ->
      val obj = el as? JsonObject ?: return@mapNotNull null
      mapElement(obj, snapshot)
    }
    RemoteHaPictureElements(
      HaPictureElementsData(
        captionUrl = image,
        placeholderIcon = Icons.Filled.Image,
        elements = elements,
      ),
      modifier = modifier,
    )
  }
}

private fun mapElement(obj: JsonObject, snapshot: HaSnapshot): HaPictureElement? {
  val type = obj["type"]?.jsonPrimitive?.content ?: return null
  val entityId = obj["entity"]?.jsonPrimitive?.content
  val entity = entityId?.let { snapshot.states[it] }
  val position = parsePositionFraction(obj)
  return when (type) {
    "state-icon",
    "icon" ->
      HaPictureElement.StateIcon(
        entityId = entityId,
        icon = HaIconMap.resolve(obj["icon"]?.jsonPrimitive?.content, entity),
        accent = HaStateColor.activeFor(entity),
        initiallyActive = entity != null && HaLiveBindings.isOn(entityId, entity.state),
        tapAction = parseTap(obj, entityId),
        position = position,
      )
    "state-label" ->
      HaPictureElement.StateLabel(
        entityId = entityId,
        text = LiveValues.state(entityId, formatState(entity, snapshot)),
        position = position,
      )
    "service-button" -> {
      val title = obj["title"]?.jsonPrimitive?.content ?: "Action"
      HaPictureElement.ServiceButton(
        label = title,
        accent = androidx.compose.ui.graphics.Color(0xFF1565C0),
        tapAction = parseTap(obj, entityId),
        position = position,
      )
    }
    else -> null
  }
}

private fun parseTap(obj: JsonObject, defaultEntity: String?): HaAction {
  val tapCfg = obj["tap_action"]?.jsonObject
  if (tapCfg != null) return parseHaAction(tapCfg, defaultEntity)
  return defaultTapActionFor(defaultEntity)
}

/** An element's position as whole dp on the card's canvas, as the component places it. */
private fun parsePositionDp(obj: JsonObject): Pair<Int, Int>? {
  val position = parsePositionFraction(obj) ?: return null
  return (position.leftFraction * CANVAS_W_DP).toInt().coerceAtLeast(0) to
    (position.topFraction * CANVAS_H_DP).toInt().coerceAtLeast(0)
}

/** A CSS percentage that lands on [dp] of [canvas] dp, mid-pixel so rounding can't move it. */
private fun percentFor(dp: Int, canvas: Int): String =
  "%.4f%%".format(java.util.Locale.ROOT, (dp + 0.5f) * 100f / canvas)

private const val ELEMENT_TAP = "#el-"
private const val CANVAS_W_DP = 360
private const val CANVAS_H_DP = 180

private fun parsePositionFraction(obj: JsonObject): HaPictureElementPosition? {
  val style = obj["style"] as? JsonObject ?: return null
  val left = style["left"]?.jsonPrimitive?.content?.let(::parsePercent) ?: return null
  val top = style["top"]?.jsonPrimitive?.content?.let(::parsePercent) ?: return null
  return HaPictureElementPosition(leftFraction = left, topFraction = top)
}

private fun parsePercent(raw: String): Float? {
  val trimmed = raw.trim().removeSuffix("%").trim()
  val value = trimmed.toFloatOrNull() ?: return null
  return (value / 100f).coerceIn(0f, 1f)
}
