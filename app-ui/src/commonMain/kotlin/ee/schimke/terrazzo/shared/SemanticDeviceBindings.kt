package ee.schimke.terrazzo.shared

import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.HaAction
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

private val HA_DOMAINS =
  listOf(
    "alarm_control_panel",
    "image_processing",
    "binary_sensor",
    "device_tracker",
    "input_boolean",
    "input_datetime",
    "input_button",
    "input_number",
    "input_select",
    "media_player",
    "water_heater",
    "air_quality",
    "input_text",
    "lawn_mower",
    "humidifier",
    "datetime",
    "calendar",
    "climate",
    "weather",
    "button",
    "camera",
    "number",
    "notify",
    "person",
    "remote",
    "script",
    "select",
    "sensor",
    "switch",
    "update",
    "vacuum",
    "cover",
    "event",
    "image",
    "light",
    "scene",
    "siren",
    "valve",
    "lock",
    "text",
    "time",
    "todo",
    "fan",
  )

private val ACTIONABLE_DOMAINS =
  setOf(
    "button",
    "input_button",
    "switch",
    "input_boolean",
    "light",
    "fan",
    "cover",
    "lock",
    "select",
    "input_select",
    "number",
    "input_number",
    "climate",
    "media_player",
    "vacuum",
    "valve",
    "script",
    "scene",
    "humidifier",
    "water_heater",
    "remote",
    "siren",
  )

private val TOGGLE_DOMAINS =
  setOf("light", "switch", "input_boolean", "fan", "cover", "media_player", "lock")

private val SLOT_KEY = Regex("^[a-z0-9_]+\\.s\\d+\\.")

internal fun resolveSemanticDeviceId(
  card: CardConfig,
  snapshot: HaSnapshot,
  explicitDeviceId: String?,
  explicitBindings: JsonObject?,
): String? {
  explicitDeviceId
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let {
      return it
    }
  val candidates = buildList {
    CardDesignSlots.slotEntries(card).orEmpty().forEach { add(it.entityId) }
    explicitBindings?.values?.forEach { entry -> bindingEntityId(entry)?.let { add(it) } }
  }
  return candidates.firstNotNullOfOrNull {
    snapshot.devices[it]?.deviceId?.takeIf(String::isNotBlank)
  }
}

internal fun discoverDeviceSibling(
  varName: String,
  deviceId: String?,
  snapshot: HaSnapshot,
  preferActionable: Boolean = false,
): String? {
  if (deviceId.isNullOrBlank()) return null
  val key = varName.removePrefix("USER:").trim().lowercase()
  if (key.isEmpty()) return null
  data class Sibling(
    val entityId: String,
    val domain: String,
    val objectId: String,
    val translationKey: String?,
  )
  val siblings =
    snapshot.devices.entries.mapNotNull { (entityId, info) ->
      if (info.deviceId != deviceId) return@mapNotNull null
      val dot = entityId.indexOf('.')
      if (dot < 0) return@mapNotNull null
      Sibling(
        entityId = entityId,
        domain = entityId.substring(0, dot).lowercase(),
        objectId = entityId.substring(dot + 1).lowercase(),
        translationKey = info.translationKey?.lowercase(),
      )
    }
  if (siblings.isEmpty()) return null

  fun pick(candidates: List<Sibling>, suffix: String): String? {
    val ordered =
      if (preferActionable)
        candidates.filter { it.domain in ACTIONABLE_DOMAINS } +
          candidates.filterNot { it.domain in ACTIONABLE_DOMAINS }
      else candidates
    val tail = "_$suffix"
    return ordered.firstOrNull { it.translationKey == suffix }?.entityId
      ?: ordered.firstOrNull { it.objectId == suffix }?.entityId
      ?: ordered.firstOrNull { it.objectId.endsWith(tail) }?.entityId
      ?: ordered.firstOrNull { it.translationKey?.endsWith(tail) == true }?.entityId
  }

  for (domain in HA_DOMAINS) {
    val prefix = "${domain}_"
    if (key.startsWith(prefix) && key.length > prefix.length) {
      val suffix = key.removePrefix(prefix)
      pick(siblings.filter { it.domain == domain }, suffix)?.let {
        return it
      }
    }
  }
  return pick(siblings, key)
}

internal fun semanticDeviceBindings(
  card: CardConfig,
  snapshot: HaSnapshot,
  bytes: ByteArray?,
  explicitBindings: JsonObject?,
  explicitDeviceId: String?,
  existing: Map<String, RcNamedValue>,
): Map<String, RcNamedValue> {
  val declared =
    bytes
      ?.takeIf { it.isNotEmpty() }
      ?.let { runCatching { RcDocumentCodec.decode(it) }.getOrNull() }
      ?.operations
      ?.filterIsInstance<RcNamedVariable>()
      ?.associate { it.name.removePrefix("USER:") to it.type }
      .orEmpty()
  if (explicitBindings == null && declared.isEmpty()) return emptyMap()

  val out = linkedMapOf<String, RcNamedValue>()
  explicitBindings?.forEach { (rawKey, spec) ->
    val key = rawKey.removePrefix("USER:")
    if (key.isEmpty()) return@forEach
    val type = declared[key] ?: RcNamedVariable.STRING_TYPE
    if (type == RcNamedVariable.COLOR_TYPE) return@forEach
    out[key] = formatSemanticValue(spec, snapshot.states, type)
  }

  val deviceId = resolveSemanticDeviceId(card, snapshot, explicitDeviceId, explicitBindings)
  if (deviceId != null) {
    for ((key, type) in declared) {
      if (
        type == RcNamedVariable.COLOR_TYPE ||
          key in out ||
          key in existing ||
          key.startsWith("meta.") ||
          key.startsWith("host.") ||
          key.startsWith("HA:") ||
          key == "card.title" ||
          SLOT_KEY.containsMatchIn(key)
      ) {
        continue
      }
      val sibling = discoverDeviceSibling(key, deviceId, snapshot) ?: continue
      out[key] = formatSemanticValue(JsonPrimitive(sibling), snapshot.states, type)
    }
  }
  return out
}

internal data class ResolvedSemanticAction(
  val action: HaAction,
  val target: JsonObject? = null,
)

internal fun semanticActionPayload(resolved: ResolvedSemanticAction): String {
  val base = cardActionPayload(resolved.action)
  val target = resolved.target?.takeIf { it.isNotEmpty() } ?: return base
  val obj = Json.parseToJsonElement(base).jsonObject
  return JsonObject(obj + ("target" to target)).toString()
}

internal fun resolveSemanticAction(
  card: CardConfig,
  name: String,
  value: String?,
  snapshot: HaSnapshot,
  explicitActions: JsonObject?,
  explicitBindings: JsonObject?,
  explicitDeviceId: String?,
): ResolvedSemanticAction? {
  val trimmed = name.trim()
  if (trimmed.isEmpty()) return null
  val primary = CardDesignSlots.slotEntries(card).orEmpty().firstOrNull()?.entityId
  var actionKey = trimmed
  var actionValue = value?.trim().orEmpty()
  if ((actionKey == "ha.device_action" || actionKey == "ha.action") && actionValue.isNotEmpty()) {
    actionKey = actionValue
    actionValue = ""
  }

  if (explicitActions != null) {
    val compound = if (actionValue.isNotEmpty()) "$actionKey:$actionValue" else null
    val cfg =
      (compound?.let { explicitActions[it] })
        ?: explicitActions[actionKey]
        ?: explicitActions[trimmed]
    if (cfg != null) return parseConfiguredAction(cfg, primary, actionValue)
  }

  val deviceId = resolveSemanticDeviceId(card, snapshot, explicitDeviceId, explicitBindings)
  val sibling = discoverDeviceSibling(actionKey, deviceId, snapshot, preferActionable = true)
  if (sibling != null) return ResolvedSemanticAction(defaultEntityAction(sibling, actionValue))
  return null
}

internal fun resolveSemanticNamedAction(
  card: CardConfig,
  name: String,
  value: String?,
  snapshot: HaSnapshot,
  explicitActions: JsonObject?,
  explicitBindings: JsonObject?,
  explicitDeviceId: String?,
): HaAction? =
  resolveSemanticAction(
      card,
      name,
      value,
      snapshot,
      explicitActions,
      explicitBindings,
      explicitDeviceId,
    )
    ?.action

private fun bindingEntityId(spec: JsonElement): String? =
  when (spec) {
    is JsonPrimitive -> spec.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    is JsonObject ->
      (spec["entity"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    else -> null
  }

private fun formatSemanticValue(
  spec: JsonElement,
  states: Map<String, EntityState>,
  variableType: Int,
): RcNamedValue {
  val obj = spec as? JsonObject
  val entityId = bindingEntityId(spec)
  val entity = entityId?.let { states[it] }
  val attribute =
    (obj?.get("attribute") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
  val rawPrimitive: JsonPrimitive? =
    if (attribute != null) entity?.attributes?.get(attribute) as? JsonPrimitive
    else entity?.state?.let(::JsonPrimitive)
  val rawText = rawPrimitive?.contentOrNull
  val unavailable =
    rawText == null ||
      rawText.isEmpty() ||
      (attribute == null && (rawText == "unavailable" || rawText == "unknown"))
  val hitDefault = matchesDefaultIf(rawText, obj?.get("default_if"))
  val uppercase = (obj?.get("uppercase") as? JsonPrimitive)?.booleanOrNull == true
  val fallback = obj?.get("fallback") as? JsonPrimitive

  if (unavailable || hitDefault) {
    return when (variableType) {
      RcNamedVariable.FLOAT_TYPE -> RcNamedValue.FloatValue(fallback?.doubleOrNull?.toFloat() ?: 0f)
      RcNamedVariable.INT_TYPE ->
        RcNamedValue.Integer(
          when {
            fallback?.booleanOrNull != null -> if (fallback.booleanOrNull == true) 1 else 0
            fallback?.doubleOrNull != null -> fallback.doubleOrNull!!.roundToInt()
            else -> 0
          },
        )
      else -> {
        val text =
          fallback?.contentOrNull ?: if (hitDefault || attribute != null) "" else "Unavailable"
        RcNamedValue.Text(if (uppercase) text.uppercase() else text)
      }
    }
  }

  val scale = (obj?.get("scale") as? JsonPrimitive)?.doubleOrNull
  val rawNum = rawPrimitive.doubleOrNull ?: rawText.toDoubleOrNull()
  val num = rawNum?.let { if (scale != null) it * scale else it }
  val active =
    if (attribute == null && entityId != null) isEntityActive(entityId, rawText)
    else
      rawText.lowercase() !in
        setOf("", "0", "false", "off", "closed", "unavailable", "unknown", "none")

  if (variableType == RcNamedVariable.FLOAT_TYPE) {
    return RcNamedValue.FloatValue(num?.toFloat() ?: if (active) 1f else 0f)
  }
  if (variableType == RcNamedVariable.INT_TYPE) {
    if (rawText.lowercase() in setOf("on", "off", "true", "false")) {
      return RcNamedValue.Integer(if (active) 1 else 0)
    }
    if (num != null) return RcNamedValue.Integer(num.roundToInt())
    return RcNamedValue.Integer(if (active) 1 else 0)
  }

  val onText = (obj?.get("on_text") as? JsonPrimitive)?.contentOrNull
  val offText = (obj?.get("off_text") as? JsonPrimitive)?.contentOrNull
  val prefix = (obj?.get("prefix") as? JsonPrimitive)?.contentOrNull.orEmpty()
  val suffix = (obj?.get("suffix") as? JsonPrimitive)?.contentOrNull
  val fmt = (obj?.get("format") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase().orEmpty()
  var text =
    when {
      active && onText != null -> onText
      !active && offText != null -> offText
      fmt == "raw" -> if (num != null && scale != null) formatShortNumber(num) else rawText
      fmt in setOf("int", "integer", "round") -> num?.roundToInt()?.toString() ?: rawText
      fmt in setOf("float", "number") -> num?.let(::formatShortNumber) ?: rawText
      fmt in setOf("percent", "pct") ->
        num?.let { "${it.roundToInt()}${if (suffix != null) "" else "%"}" } ?: rawText
      fmt in setOf("title", "capitalize") ->
        rawText.replace('_', ' ').replaceFirstChar { it.uppercase() }
      fmt in setOf("lower", "lowercase") -> rawText.lowercase()
      fmt in setOf("upper", "uppercase") -> rawText.uppercase()
      num != null && (scale != null || suffix != null) -> formatShortNumber(num)
      attribute != null -> rawText
      else -> {
        val unit = (entity?.attributes?.get("unit_of_measurement") as? JsonPrimitive)?.contentOrNull
        if (unit != null && num != null) "${formatShortNumber(num)} $unit" else rawText
      }
    }
  text = prefix + text + (suffix ?: "")
  if (uppercase) text = text.uppercase()
  return RcNamedValue.Text(text)
}

private fun matchesDefaultIf(raw: String?, defaultIf: JsonElement?): Boolean {
  if (raw == null || defaultIf == null) return false
  val candidates =
    when (defaultIf) {
      is JsonArray -> defaultIf.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
      is JsonPrimitive -> listOfNotNull(defaultIf.contentOrNull)
      else -> emptyList()
    }
  return candidates.any { it.equals(raw, ignoreCase = true) }
}

private fun isEntityActive(entityId: String, state: String): Boolean =
  when (entityId.substringBefore('.')) {
    "cover" -> state in setOf("open", "opening", "closing")
    "lock" -> state == "locked"
    "person",
    "device_tracker" -> state == "home"
    "vacuum" -> state in setOf("cleaning", "returning")
    else -> state == "on" || state == "open"
  }

private fun formatShortNumber(d: Double): String {
  if (!d.isFinite()) return d.toString()
  val tenths = floor(d * 10.0 + 0.5).toLong()
  if (tenths % 10L == 0L) return (tenths / 10L).toString()
  val sign = if (tenths < 0) "-" else ""
  val absTenths = abs(tenths)
  return "$sign${absTenths / 10L}.${absTenths % 10L}"
}

private fun defaultEntityAction(entityId: String, value: String): HaAction {
  val domain = entityId.substringBefore('.')
  return when {
    domain in setOf("button", "input_button") -> HaAction.CallService(domain, "press", entityId)
    domain in setOf("script", "scene") -> HaAction.CallService(domain, "turn_on", entityId)
    domain in setOf("select", "input_select") && value.isNotEmpty() ->
      HaAction.CallService(
        domain,
        "select_option",
        entityId,
        JsonObject(mapOf("option" to JsonPrimitive(value))),
      )
    domain in setOf("number", "input_number") && value.toDoubleOrNull() != null ->
      HaAction.CallService(
        domain,
        "set_value",
        entityId,
        JsonObject(mapOf("value" to JsonPrimitive(value.toDouble()))),
      )
    domain in TOGGLE_DOMAINS -> HaAction.Toggle(entityId)
    else -> HaAction.MoreInfo(entityId)
  }
}

private fun parseConfiguredAction(
  cfg: JsonElement,
  defaultEntity: String?,
  value: String,
): ResolvedSemanticAction? {
  if (cfg is JsonPrimitive) {
    val id = cfg.contentOrNull?.trim().orEmpty()
    return if ('.' in id) ResolvedSemanticAction(defaultEntityAction(id, value)) else null
  }
  val obj = cfg as? JsonObject ?: return null
  val explicitEntity =
    (obj["entity"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
      ?: (obj["entity_id"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
  val entityId = explicitEntity ?: defaultEntity
  val svc =
    (obj["perform_action"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { '.' in it }
      ?: (obj["service"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { '.' in it }
  val action = (obj["action"] as? JsonPrimitive)?.contentOrNull?.trim()
  if (svc != null || action in setOf("call-service", "perform-action")) {
    val resolvedSvc = svc ?: return null
    val data =
      (obj["data"] as? JsonObject) ?: (obj["service_data"] as? JsonObject) ?: JsonObject(emptyMap())
    val targetObj = (obj["target"] as? JsonObject)?.takeIf { it.isNotEmpty() }
    val targetEntity =
      if (targetObj != null) {
        (targetObj["entity_id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
      } else {
        entityId
      }
    return ResolvedSemanticAction(
      action =
        HaAction.CallService(
          domain = resolvedSvc.substringBefore('.'),
          service = resolvedSvc.substringAfter('.'),
          entityId = targetEntity,
          serviceData = data,
        ),
      target = targetObj,
    )
  }
  val resolved =
    when (action) {
      "toggle" -> entityId?.let(HaAction::Toggle)
      "more-info" -> entityId?.let(HaAction::MoreInfo)
      "navigate" ->
        (obj["navigation_path"] as? JsonPrimitive)?.contentOrNull?.let(HaAction::Navigate)
      "url" -> (obj["url_path"] as? JsonPrimitive)?.contentOrNull?.let(HaAction::Url)
      "none" -> null
      else -> {
        if (entityId == null) return null
        val domain = entityId.substringBefore('.')
        val opt = (obj["option"] as? JsonPrimitive)?.contentOrNull
        val num = (obj["value"] as? JsonPrimitive)?.doubleOrNull
        when {
          domain in setOf("select", "input_select") && opt != null ->
            HaAction.CallService(
              domain,
              "select_option",
              entityId,
              JsonObject(mapOf("option" to JsonPrimitive(opt))),
            )
          domain in setOf("number", "input_number") && num != null ->
            HaAction.CallService(
              domain,
              "set_value",
              entityId,
              JsonObject(mapOf("value" to JsonPrimitive(num))),
            )
          else -> defaultEntityAction(entityId, value)
        }
      }
    }
  return resolved?.let(::ResolvedSemanticAction)
}
