package ee.schimke.terrazzo.shared

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.rcplayer.runtime.RcHostActionValue
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.composeai.rcplayer.runtime.RcPlayerEvent
import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.client.design.UsageSource
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.isRelativeTimeState
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

/** Values stay in the host. Resolution sends only card type and anonymous domain slots. */
@Composable
internal fun LibraryCard(
  card: CardConfig,
  snapshot: HaSnapshot,
  style: String,
  dark: Boolean,
  onToggle: ((String) -> Unit)? = null,
  onMoreInfo: ((String) -> Unit)? = null,
  onTemperatureChange: ((Double) -> Unit)? = null,
  showFallbackNotice: Boolean = true,
  requireExactDesign: Boolean = false,
  designCache: DashboardLoadCache<LibraryDesignKey, ByteArray?>? = null,
  /** Hears, once the design lookup settles, whether the card drew from a design or [fallback]. */
  onSource: ((UsageSource) -> Unit)? = null,
  fallback: @Composable () -> Unit,
) {
  val scope = rememberCoroutineScope()
  val cache =
    designCache
      ?: remember(scope) {
        DashboardLoadCache(scope) { key: LibraryDesignKey -> loadLibraryDesign(key) }
      }
  val designKey =
    remember(card, style, dark, requireExactDesign) {
      LibraryDesignKey(anonymousTemplateCard(card), style, dark, requireExactDesign)
    }
  var bytes by
    remember(card, designKey, cache) {
      mutableStateOf(
        cache.peek(designKey)?.takeIf { canBindWithCmp(it, templateBindings(card, snapshot)) }
      )
    }
  var settled by remember(designKey, cache) { mutableStateOf(false) }
  val hasRelativeTime =
    CardDesignSlots.slotEntries(card).orEmpty().any {
      isRelativeTimeState(snapshot.states[it.entityId])
    }
  // Same 20-second contract as the mobile host. Only relative-time cards run this timer.
  val tick by
    produceState(0L, hasRelativeTime) {
      if (hasRelativeTime)
        while (true) {
          delay(20_000)
          value++
        }
    }
  val values = remember(card, snapshot, tick) { templateBindings(card, snapshot) }
  LaunchedEffect(designKey, cache) {
    try {
      val loaded = cache.get(designKey)
      if (loaded != null && canBindWithCmp(loaded, templateBindings(card, snapshot))) bytes = loaded
    } catch (e: CancellationException) {
      throw e
    } catch (_: Throwable) {
      // Network failure follows the same explicit fallback as a missing/incompatible design.
    }
    settled = true
    onSource?.invoke(if (bytes != null) UsageSource.Remote else UsageSource.Converter)
  }
  val current = bytes
  Column {
    if (current == null) {
      Column {
        DashboardCardTheme(style, dark) { key(tick) { fallback() } }
        if (settled && showFallbackNotice) Text("Design unavailable · showing built-in card")
      }
    } else {
      Column(
        Modifier.entityDetails(
          CardDesignSlots.slotEntries(card).orEmpty().map { it.entityId }.distinct().singleOrNull(),
          onMoreInfo,
        )
      ) {
        // Theme swaps and responsive resizes should not animate text through clipped widths.
        RemoteComposeCard(current, Modifier.fillMaxWidth(), values, animateLayoutChanges = false) {
          event ->
          if (event is RcPlayerEvent.HostNamedAction && event.name == "ha") {
            val payload = (event.value as? RcHostActionValue.TextValue)?.value
            val toggle = demoToggleEntity(card, payload)
            if (toggle != null) onToggle?.invoke(toggle)
            else demoMoreInfoEntity(card, payload)?.let { onMoreInfo?.invoke(it) }
            demoThermostatTarget(card, snapshot, payload)?.let { onTemperatureChange?.invoke(it) }
          }
        }
        // Accessible host controls also work with templates without action regions.
        CardDesignSlots.slotEntries(card)
          .orEmpty()
          .map { it.entityId }
          .distinct()
          .forEach { id ->
            val entity = snapshot.states[id]
            if (id.substringBefore('.') in setOf("light", "switch")) {
              val label =
                entity?.attributes?.get("friendly_name")?.jsonPrimitive?.contentOrNull ?: id
              ListItem(
                modifier = Modifier.entityDetails(id, onMoreInfo),
                headlineContent = { Text(label) },
                trailingContent = {
                  Switch(
                    modifier = Modifier.semantics { contentDescription = label },
                    checked = entity?.state == "on",
                    enabled = onToggle != null && entity?.state in setOf("on", "off"),
                    onCheckedChange = { onToggle?.invoke(id) },
                  )
                },
              )
            }
          }
      }
    }
    if (
      card.type == "thermostat" &&
        card.raw["entity"]?.jsonPrimitive?.contentOrNull == "climate.demo_thermostat"
    ) {
      val attrs = snapshot.states["climate.demo_thermostat"]?.attributes
      val target = (attrs?.get("temperature") as? JsonPrimitive)?.doubleOrNull
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = { target?.let { onTemperatureChange?.invoke((it - 0.5).coerceAtLeast(16.0)) } },
          enabled = onTemperatureChange != null && target != null && target > 16.0,
        ) {
          Text("Cooler")
        }
        Text(target?.let { "$it °C target" } ?: "No target")
        Button(
          onClick = { target?.let { onTemperatureChange?.invoke((it + 0.5).coerceAtMost(28.0)) } },
          enabled = onTemperatureChange != null && target != null && target < 28.0,
        ) {
          Text("Warmer")
        }
      }
    }
  }
}

/** Remote bytes may select a local entity slot, never an arbitrary service or entity. */
internal fun demoToggleEntity(card: CardConfig, payload: String?): String? {
  if (payload == null) return null
  val action =
    runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
  val kind = (action["type"] as? JsonPrimitive)?.contentOrNull?.substringAfterLast('.')
  val configured =
    ((card.raw["tap_action"] as? JsonObject)?.get("action") as? JsonPrimitive)?.contentOrNull
  // A library's generic body action must honor this dashboard's host-owned toggle configuration.
  if (configured != null && configured != "toggle") return null
  if (kind != "Toggle" && !(kind == "MoreInfo" && configured == "toggle")) return null
  val slot = (action["entityId"] as? JsonPrimitive)?.contentOrNull ?: return null
  return CardDesignSlots.slotEntries(card)
    .orEmpty()
    .mapIndexedNotNull { index, entry ->
      val domain = CardDesignSlots.domainOf(entry.entityId)
      entry.entityId.takeIf {
        domain in setOf("light", "switch") && slot == CardDesignSlots.slotId(domain, index)
      }
    }
    .firstOrNull()
}

internal fun anonymousTemplateCard(card: CardConfig): JsonObject {
  val slots =
    CardDesignSlots.slotEntries(card).orEmpty().mapIndexed { index, entry ->
      CardDesignSlots.slotId(CardDesignSlots.domainOf(entry.entityId), index)
    }
  return buildJsonObject {
    put("type", card.type)
    if (card.raw["entity"] != null && slots.isNotEmpty()) put("entity", slots.first())
    else if (slots.isNotEmpty()) put("entities", JsonArray(slots.map(::JsonPrimitive)))
  }
}

internal fun templateBindings(card: CardConfig, snapshot: HaSnapshot): Map<String, RcNamedValue> {
  val slots = CardDesignSlots.slotBindings(card, snapshot)
  return buildMap {
    slots.strings.forEach { (k, v) -> put(k, RcNamedValue.Text(v)) }
    slots.booleans.forEach { (k, v) -> put(k, RcNamedValue.Integer(if (v) 1 else 0)) }
    slots.ints.forEach { (k, v) -> put(k, RcNamedValue.Integer(v)) }
    slots.floats.forEach { (k, v) -> put(k, RcNamedValue.FloatValue(v)) }
    putAll(instrumentTemplateBindings(card, snapshot))
  }
}

internal fun demoMoreInfoEntity(card: CardConfig, payload: String?): String? {
  val action =
    payload?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
      ?: return null
  val kind = (action["type"] as? JsonPrimitive)?.contentOrNull?.substringAfterLast('.')
  val configured =
    ((card.raw["tap_action"] as? JsonObject)?.get("action") as? JsonPrimitive)?.contentOrNull
  if (
    !((kind == "MoreInfo" && configured in setOf(null, "more-info", "toggle")) ||
      (kind == "Toggle" && configured == "more-info"))
  )
    return null
  val slot = (action["entityId"] as? JsonPrimitive)?.contentOrNull ?: return null
  return CardDesignSlots.slotEntries(card)
    .orEmpty()
    .mapIndexedNotNull { index, entry ->
      entry.entityId.takeIf { slot == CardDesignSlots.slotId(CardDesignSlots.domainOf(it), index) }
    }
    .firstOrNull()
}

/** Match the default thermostat template's ±0.5° actions, then rebuild from live HA state. */
internal fun demoThermostatTarget(
  card: CardConfig,
  snapshot: HaSnapshot,
  payload: String?,
): Double? {
  if (
    card.type != "thermostat" ||
      card.raw["entity"]?.jsonPrimitive?.contentOrNull != "climate.demo_thermostat"
  )
    return null
  val action =
    payload?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
      ?: return null
  fun text(key: String) = (action[key] as? JsonPrimitive)?.contentOrNull
  if (
    text("type")?.substringAfterLast('.') != "CallService" ||
      text("domain") != "climate" ||
      text("service") != "set_temperature" ||
      text("entityId") != "climate.s0"
  )
    return null
  val data = action["serviceData"] as? JsonObject ?: return null
  if (data.keys != setOf("temperature")) return null
  // DialCard's placeholder is 20°C. These literals identify direction, never the requested
  // setpoint.
  val delta =
    when ((data["temperature"] as? JsonPrimitive)?.doubleOrNull) {
      20.5 -> 0.5
      19.5 -> -0.5
      else -> return null
    }
  val current =
    (snapshot.states["climate.demo_thermostat"]?.attributes?.get("temperature") as? JsonPrimitive)
      ?.doubleOrNull
      ?.takeIf { it.isFinite() && it in 16.0..28.0 } ?: return null
  return (current + delta).coerceIn(16.0, 28.0).takeUnless { it == current }
}

/**
 * Only an exact structural match is playable. Domain-only guesses can bake a thermometer for any
 * sensor, and can bake the wrong availability layout or theme. Never send private state.
 */
internal data class LibraryDesignKey(
  val card: JsonObject,
  val style: String,
  val dark: Boolean,
  val requireExact: Boolean = false,
)

internal fun exactLibraryDesign(response: JsonObject): Boolean =
  (response["exact"] as? JsonPrimitive)?.booleanOrNull == true

internal const val DEFAULT_CARD_DESIGN_SERVER = "https://ha.coo.ee"

/** The configured "Card design server" origin; blank falls back to the hosted library. */
internal fun cardDesignServerBase(server: String): String =
  server.trim().trimEnd('/').ifEmpty { DEFAULT_CARD_DESIGN_SERVER }

internal fun cardDesignResolveUrl(server: String): String =
  "${cardDesignServerBase(server)}/api/cards/resolve"

/** The design server's published-designs API: manifest, blobs and the usage endpoint. */
internal fun cardDesignApiBase(server: String): String =
  "${cardDesignServerBase(server)}/designs/v1"

/**
 * A design blob's URL. [client] is the app's fixed release tag (`UsageUserAgent.header`), sent as a
 * query parameter because a browser page can't set its own User-Agent; the server counts the fetch
 * under it (phase 1 of docs/followups/template-usage-tracking.md).
 */
internal fun cardDesignBlobUrl(server: String, sha: String, client: String? = null): String =
  "${cardDesignApiBase(server)}/blobs/$sha.rc" +
    (client?.let { "?client=" + it.encodeURLParameter() } ?: "")

internal suspend fun loadLibraryDesign(
  key: LibraryDesignKey,
  server: String = DEFAULT_CARD_DESIGN_SERVER,
  client: String? = null,
): ByteArray? {
  val http = HttpClient()
  try {
    val response =
      http.post(cardDesignResolveUrl(server)) {
        contentType(ContentType.Text.Plain)
        setBody(
          buildJsonObject {
            put("card", key.card)
            put("cardStyle", key.style)
            put("dark", key.dark)
          }
            .toString()
        )
      }
    if (response.status == HttpStatusCode.NotFound) return null
    check(response.status.isSuccess())
    val resolved = Json.parseToJsonElement(response.bodyAsText()).jsonObject
    if (key.requireExact && !exactLibraryDesign(resolved)) return null
    val sha = resolved["sha256"]!!.jsonPrimitive.content
    check(Regex("[0-9a-f]{64}").matches(sha))
    val blob = http.get(cardDesignBlobUrl(server, sha, client))
    check(blob.status.isSuccess())
    return blob.bodyAsBytes().also { check(Sha256.hex(it) == sha) }
  } finally {
    http.close()
  }
}
