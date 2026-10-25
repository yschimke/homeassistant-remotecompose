package ee.schimke.terrazzo.shared

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.GeneratedDesign
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.SystemHaClock
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.isRelativeTimeState
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/**
 * Values stay in the host. A card with a template shape is asked for by its exact [DesignKey]
 * ([exactDesignKey], computed here as the phone app does), and draws with its converter's own
 * bindings; any other sends only its card type and anonymous domain slots to `/api/cards/resolve`.
 */
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
  val exact = remember(card, snapshot, style, dark) { exactDesignKey(card, snapshot, style, dark) }
  val hint = remember(card, snapshot, exact) { cacheShapeHint(exact, card, snapshot) }
  val designKey =
    remember(card, hint, style, dark, requireExactDesign, exact) {
      LibraryDesignKey(anonymousTemplateCard(card), style, dark, requireExactDesign, exact, hint)
    }
  fun bindings() =
    if (exact != null) exactTemplateBindings(card, snapshot) else templateBindings(card, snapshot)
  var bytes by
    remember(card, designKey, cache) {
      mutableStateOf(cache.peek(designKey)?.takeIf { canBindWithCmp(it, bindings()) })
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
  val values = remember(card, snapshot, tick, exact) { bindings() }
  LaunchedEffect(designKey, cache) {
    try {
      val loaded = cache.get(designKey)
      if (loaded != null && canBindWithCmp(loaded, bindings())) bytes = loaded
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
        if (settled && showFallbackNotice)
          Text(
            "Design unavailable · showing built-in card",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
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

/**
 * The observable shape discriminators of a single-slot card: `device_class`, `unit` and `icon` from
 * the slot entity's attributes (the ones a tile/entity converter keys its design on), plus the
 * card's own `icon`. It carries the card's *shape*, never an entity id, name or state value — the
 * same privacy contract as [anonymousTemplateCard] — so the anonymous `/api/cards/resolve` fallback
 * (used only when there is no exact key) can resolve the one design the card's shape names instead
 * of the first of the shapes its slots fit. Empty for a card without exactly one slot (multi-slot
 * shapes key on prefixed properties the server's match skips).
 */
internal fun shapeHint(card: CardConfig, snapshot: HaSnapshot): JsonObject {
  val entry = CardDesignSlots.slotEntries(card).orEmpty().singleOrNull() ?: return EMPTY_SHAPE_HINT
  val state = snapshot.states[entry.entityId] ?: return EMPTY_SHAPE_HINT
  val domain = CardDesignSlots.domainOf(entry.entityId)
  fun attr(name: String) =
    state.attributes[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
  return buildJsonObject {
    // device_class only changes a sensor's / binary_sensor's icon and colour; other domains ignore
    // it, so the converter leaves it out of their shape (see EntityDesign.DEVICE_CLASS_DOMAINS).
    if (domain == "sensor" || domain == "binary_sensor")
      attr("device_class")?.let { put("device_class", it) }
    attr("unit_of_measurement")?.let { put("unit", it) }
    attr("icon")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { put("icon", it) }
    (card.raw["icon"] as? JsonPrimitive)
      ?.contentOrNull
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
      ?.let { put("card_icon", it) }
  }
}

private val EMPTY_SHAPE_HINT = JsonObject(emptyMap())

/**
 * The hint to fold into the design cache key: the card's observable [shapeHint] only on the
 * anonymous fallback (no [exact] key). With an exact key the server is asked by key, so the hint is
 * unused and must stay out of the key — otherwise two cards that share one exact key but read
 * different observable attributes would land in separate cache entries and resolve the same design
 * twice.
 */
internal fun cacheShapeHint(exact: DesignKey?, card: CardConfig, snapshot: HaSnapshot): JsonObject =
  if (exact == null) shapeHint(card, snapshot) else EMPTY_SHAPE_HINT

internal fun templateBindings(card: CardConfig, snapshot: HaSnapshot): Map<String, RcNamedValue> =
  CardDesignSlots.slotBindings(card, snapshot).toNamedValues() +
    instrumentTemplateBindings(card, snapshot)

/**
 * [card]'s exact design key, as the phone app computes it (`CardDesignTemplates.keyFor`): its
 * converter's generation properties for [snapshot], never values. Null when the card has no
 * template shape. [style] is the card style's id; a classic card draws in the first app palette, as
 * `/api/cards/resolve` assumes for a request without one.
 */
internal fun exactDesignKey(
  card: CardConfig,
  snapshot: HaSnapshot,
  style: String,
  dark: Boolean,
): DesignKey? = runCatching {
  CardDesignTemplates.keyFor(
    card,
    snapshot,
    ThemeStyle.entries.first(),
    dark,
    cardStyle = HaUberTheme.byId(style),
  )
}
  .getOrNull()

/** The values an exact design binds: its converter's own, as the phone app plays it. */
internal fun exactTemplateBindings(
  card: CardConfig,
  snapshot: HaSnapshot,
): Map<String, RcNamedValue> {
  val design = CardDesignTemplates.designCard(card, snapshot)
  return CardDesignTemplates.playback(design, ByteArray(0))
    .bindings(snapshot, SystemHaClock)
    .toNamedValues()
}

private fun CardSnapshotBindings.toNamedValues(): Map<String, RcNamedValue> = buildMap {
  strings.forEach { (k, v) -> put(k, RcNamedValue.Text(v)) }
  booleans.forEach { (k, v) -> put(k, RcNamedValue.Integer(if (v) 1 else 0)) }
  ints.forEach { (k, v) -> put(k, RcNamedValue.Integer(v)) }
  floats.forEach { (k, v) -> put(k, RcNamedValue.FloatValue(v)) }
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
  /** The card's exact key ([exactDesignKey]): asked for by key when set, so always exact. */
  val exact: DesignKey? = null,
  /** [shapeHint] for the card: the observable shape discriminators, empty when none apply. */
  val shapeHint: JsonObject = EMPTY_SHAPE_HINT,
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

/** The design server's design-by-key endpoint (`DesignGenerator`). */
internal fun cardDesignGenerateUrl(server: String): String = "${cardDesignApiBase(server)}/generate"

private val designJson = Json { ignoreUnknownKeys = true }

internal suspend fun loadLibraryDesign(
  key: LibraryDesignKey,
  server: String = DEFAULT_CARD_DESIGN_SERVER,
  client: String? = null,
): ByteArray? {
  key.exact?.let {
    return loadDesignByKey(key, it, server, client)
  }
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
            if (key.shapeHint.isNotEmpty()) put("shapeHint", key.shapeHint)
          }
            .toString()
        )
      }
    if (response.status == HttpStatusCode.NotFound) {
      LibraryDesignMisses.record(key, null)
      return null
    }
    check(response.status.isSuccess())
    val resolved = Json.parseToJsonElement(response.bodyAsText()).jsonObject
    if (key.requireExact && !exactLibraryDesign(resolved)) {
      LibraryDesignMisses.record(key, resolved)
      return null
    }
    val sha = resolved["sha256"]!!.jsonPrimitive.content
    check(Regex("[0-9a-f]{64}").matches(sha))
    val blob = http.get(cardDesignBlobUrl(server, sha, client))
    check(blob.status.isSuccess())
    return blob.bodyAsBytes().also { check(Sha256.hex(it) == sha) }
  } finally {
    http.close()
  }
}

/**
 * [exact]'s design: the server's library design for the key, or one it captures now. Only the key
 * is sent: generation properties, never a card's config or states. Null (the built-in card) when
 * the server has no design for it.
 */
private suspend fun loadDesignByKey(
  key: LibraryDesignKey,
  exact: DesignKey,
  server: String,
  client: String?,
): ByteArray? {
  val http = HttpClient()
  try {
    val response =
      http.post(cardDesignGenerateUrl(server)) {
        // A simple request, as for resolve: no CORS preflight.
        contentType(ContentType.Text.Plain)
        setBody(designJson.encodeToString(DesignKey.serializer(), exact))
      }
    if (response.status == HttpStatusCode.NotFound) {
      LibraryDesignMisses.record(key, null)
      return null
    }
    check(response.status.isSuccess())
    val design = designJson.decodeFromString(GeneratedDesign.serializer(), response.bodyAsText())
    check(design.key == exact && Regex("[0-9a-f]{64}").matches(design.sha256))
    val blob = http.get(cardDesignBlobUrl(server, design.sha256, client))
    check(blob.status.isSuccess())
    return blob.bodyAsBytes().also { check(Sha256.hex(it) == design.sha256) }
  } finally {
    http.close()
  }
}

/**
 * Cards the design library couldn't draw exactly, so they fell back to the built-in card: the list
 * the library's coverage work enumerates next. Each distinct miss is logged once (the browser
 * console, or stdout on desktop) and counted for the session. Only what was already sent to the
 * design server appears: the anonymous card (type and slot ids), its [shapeHint] (device_class,
 * unit, icon — the card's shape, never an id, name or value), the style and light or dark, and the
 * shape the server answered with. No entity id, name or state value leaves the device.
 */
internal object LibraryDesignMisses {
  private val mutex = Mutex()
  private val counts = linkedMapOf<String, Int>()

  /** Each miss, as [describe] words it, with how often it happened this session. */
  suspend fun snapshot(): Map<String, Int> = mutex.withLock { counts.toMap() }

  suspend fun record(key: LibraryDesignKey, resolved: JsonObject?) {
    val miss = describe(key, resolved)
    val first = mutex.withLock {
      val seen = counts[miss] ?: 0
      counts[miss] = seen + 1
      seen == 0
    }
    if (first) println("Terrazzo: no exact card design · $miss")
  }

  /** A [resolved] of null means the server has no design with these slots at all (404). */
  internal fun describe(key: LibraryDesignKey, resolved: JsonObject?): String {
    val mode = if (key.dark) "dark" else "light"
    val answer =
      if (resolved == null)
        "none" + (key.exact?.shape?.takeIf { it.isNotEmpty() }?.let { " (key shape $it)" } ?: "")
      else {
        val shape =
          ((resolved["key"] as? JsonObject)?.get("shape") as? JsonPrimitive)?.contentOrNull
        "inexact" + (shape?.takeIf { it.isNotEmpty() }?.let { " (shape $it)" } ?: "")
      }
    return "${key.card} ${key.style}/$mode → $answer"
  }
}
