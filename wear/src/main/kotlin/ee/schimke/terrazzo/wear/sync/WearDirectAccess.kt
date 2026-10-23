package ee.schimke.terrazzo.wear.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import ee.schimke.ha.client.HaAuthInvalidException
import ee.schimke.ha.client.HaEntityStates
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.bambuEntityIds
import ee.schimke.ha.rc.displayPrecisionOf
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import ee.schimke.terrazzo.wearsync.proto.LiveValues
import ee.schimke.terrazzo.wearsync.proto.WEAR_EXTRA_ATTRIBUTES
import ee.schimke.terrazzo.wearsync.proto.WearAccess
import ee.schimke.terrazzo.wearsync.proto.WearAccessRequest
import ee.schimke.terrazzo.wearsync.proto.WearSettings
import ee.schimke.terrazzo.wearsync.proto.WearSyncPaths
import ee.schimke.terrazzo.wearsync.proto.decodeProto
import ee.schimke.terrazzo.wearsync.proto.encodeProto
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The watch reading its cards' values from Home Assistant itself, when the phone has sent none
 * lately: with the phone out of range, or its app not running. It uses a long-lived token the phone
 * made for this watch ([WearAccess], kept in [WearAccessStore]), asking the phone for one when it
 * has none ([requestIfMissing]), and reads only the entities its cards name, one REST request each
 * ([HaEntityStates]).
 *
 * The token is forgotten on sign-out, when the phone signs in to another instance or turns on demo
 * mode, and when Home Assistant refuses it (the watch then asks for a new one).
 */
class WearDirectAccess(
  private val context: Context,
  private val store: WearAccessStore = WearAccessStore(context),
  private val offline: WearOfflineStore = WearOfflineStore(context),
  private val fetch:
    suspend (baseUrl: String, token: String, ids: Set<String>) -> Map<
        String,
        EntityState,
      > =
    { baseUrl, token, ids ->
      HaEntityStates(baseUrl, token).use { it.fetch(ids) }
    },
  private val sendRequest: suspend () -> Unit = { sendAccessRequest(context) },
  private val now: () -> Long = System::currentTimeMillis,
) {
  /**
   * Keeps [access] when it is for the instance the phone is signed in to ([settings]), and forgets
   * any other token; returns whether it was kept.
   */
  fun accept(access: WearAccess, settings: WearSettings?): Boolean {
    if (settings == null || !signedInTo(settings, access.baseUrl) || access.token.isEmpty()) {
      return false
    }
    store.write(access)
    return true
  }

  /** Forgets the token: sign-out. */
  fun forget() = store.clear()

  /**
   * Asks the phone for a token when the watch is signed in ([settings]) and has none for that
   * instance, at most every [REQUEST_EVERY_MS]. A token for another instance, or one kept in demo
   * mode, is forgotten.
   */
  suspend fun requestIfMissing(settings: WearSettings?) {
    if (settings == null || settings.demoMode || settings.baseUrl.isEmpty()) {
      store.read()?.let { store.clear() }
      return
    }
    val kept = store.read()
    if (kept != null && signedInTo(settings, kept.baseUrl)) return
    if (kept != null) store.clear()
    val last = lastRequestAtMs
    if (last != null && now() - last < REQUEST_EVERY_MS) return
    lastRequestAtMs = now()
    runCatching { sendRequest() }
      .onFailure {
        if (it is CancellationException) throw it
        Log.w(TAG, "access request failed", it)
      }
  }

  /**
   * Reads the values of the entities the cards in [cardsRawJson] name from Home Assistant, when the
   * phone's are stale ([needsDirectValues]), and keeps them with what the phone sent; returns the
   * values kept, or null when it read nothing. [retained] says the phone no longer shows these
   * cards (a widget kept one it unpinned), so it sends none of their values: a missing one is
   * stale.
   */
  suspend fun refresh(
    cardsRawJson: List<String>,
    settings: WearSettings?,
    retained: Boolean = false,
  ): LiveValues? {
    if (settings == null || settings.demoMode || settings.baseUrl.isEmpty()) {
      // Signed out or in demo mode: no token is kept, and nothing is read.
      requestIfMissing(settings)
      return null
    }
    val stored = offline.readValues()
    val ids = watchEntityIds(cardsRawJson, stored?.values?.keys.orEmpty())
    if (!needsDirectValues(stored, ids, now(), lastDirectAtMs, retained)) return null
    val access = store.read()?.takeIf { signedInTo(settings, it.baseUrl) }
    if (access == null) {
      requestIfMissing(settings)
      return null
    }
    lastDirectAtMs = now()
    val states = fetchFromAny(access, ids) ?: return null
    val snapshot = HaSnapshot(states = states)
    val fetched = states.mapValues { (_, state) -> state.toWearEntityValue(snapshot) }
    // Every entity asked for takes what Home Assistant answered: one it no longer has (a 404) is
    // dropped rather than kept at the phone's old value. Newer than the phone's: a later phone
    // capture still replaces these as it arrives.
    val merged = LiveValues(values = stored?.values.orEmpty() - ids + fetched, capturedAtMs = now())
    // A phone capture that landed while this one read wins.
    valuesNotOlder(merged, offline.readValues())?.let(offline::writeValues) ?: return null
    return merged
  }

  /** [ids]' states from the first of [access]'s addresses that answers, or null if none does. */
  private suspend fun fetchFromAny(
    access: WearAccess,
    ids: Set<String>,
  ): Map<String, EntityState>? {
    for (baseUrl in candidateUrls(access)) {
      try {
        withTimeoutOrNull(FETCH_TIMEOUT_MS) { fetch(baseUrl, access.token, ids) }
          ?.let {
            return it
          }
      } catch (e: CancellationException) {
        throw e
      } catch (e: HaAuthInvalidException) {
        // Revoked or expired: drop it and ask for a new one.
        store.clear()
        lastRequestAtMs = null
        runCatching { sendRequest() }
        return null
      } catch (e: Exception) {
        Log.w(TAG, "direct read from $baseUrl failed", e)
      }
    }
    return null
  }

  private fun signedInTo(settings: WearSettings, baseUrl: String): Boolean =
    !settings.demoMode && settings.baseUrl.isNotEmpty() && sameUrl(settings.baseUrl, baseUrl)

  companion object {
    private const val TAG = "WearDirect"

    /** Phone values older than this are read from Home Assistant instead. */
    const val STALE_AFTER_MS = 2 * 60_000L

    /** The watch reads directly at most this often, whatever is missing. */
    const val MIN_INTERVAL_MS = 60_000L

    /** How often the watch may ask the phone for a token while it has none. */
    const val REQUEST_EVERY_MS = 10 * 60_000L

    private const val FETCH_TIMEOUT_MS = 15_000L

    // Per process: the app, its widgets and the listener service share them.
    @Volatile private var lastRequestAtMs: Long? = null
    @Volatile private var lastDirectAtMs: Long? = null

    /** Clears the per-process throttles, for tests. */
    internal fun resetThrottles() {
      lastRequestAtMs = null
      lastDirectAtMs = null
    }
  }
}

/**
 * Whether the watch should read its values from Home Assistant: the phone's ([stored]) are older
 * than [WearDirectAccess.STALE_AFTER_MS], or there are none; but never more often than
 * [WearDirectAccess.MIN_INTERVAL_MS] after the last direct read ([lastDirectAtMs]), and only when
 * the cards name entities ([ids]).
 *
 * An entity missing from fresh phone values isn't a reason: the phone sends every entity the cards
 * name that Home Assistant has, so one it lacks is one Home Assistant lacks too (or a token in a
 * card that only looks like an entity id). Except for [retained] cards, which the phone no longer
 * shows and so sends no values for: one of theirs missing is read, and theirs are read again once
 * the last direct read is [WearDirectAccess.STALE_AFTER_MS] old.
 */
internal fun needsDirectValues(
  stored: LiveValues?,
  ids: Set<String>,
  now: Long,
  lastDirectAtMs: Long?,
  retained: Boolean = false,
): Boolean {
  if (ids.isEmpty()) return false
  if (lastDirectAtMs != null && now - lastDirectAtMs < WearDirectAccess.MIN_INTERVAL_MS)
    return false
  if (stored == null || now - stored.capturedAtMs >= WearDirectAccess.STALE_AFTER_MS) return true
  if (!retained) return false
  // The stream keeps advancing capturedAtMs and keeps a retained card's old keys, so its values are
  // only as fresh as the last direct read.
  return !stored.values.keys.containsAll(ids) ||
    lastDirectAtMs == null ||
    now - lastDirectAtMs >= WearDirectAccess.STALE_AFTER_MS
}

/**
 * The addresses to read from, `https` first: Home Assistant's external URL is usually the one that
 * answers away from home, and a bearer token should go over plain `http` only when nothing else
 * does (the phone's LAN address).
 */
internal fun candidateUrls(access: WearAccess): List<String> =
  listOf(access.baseUrl, access.externalUrl)
    .map { it.trim().trimEnd('/') }
    .filter { it.startsWith("https://") || it.startsWith("http://") }
    .distinct()
    .sortedBy { if (it.startsWith("https://")) 0 else 1 }

private fun sameUrl(a: String, b: String): Boolean =
  a.trim().trimEnd('/').equals(b.trim().trimEnd('/'), ignoreCase = true)

/**
 * The entities the watch's cards ([rawJson]) read, as the phone picks them: every entity id a card
 * names, and for a Bambu Lab card its printers' entities among those the watch knows ([known]).
 */
internal fun watchEntityIds(rawJson: List<String>, known: Collection<String>): Set<String> {
  val knownSnapshot = HaSnapshot(states = known.associateWith { EntityState(it, "") })
  return rawJson.flatMapTo(HashSet()) { raw ->
    val bambu = raw.toCardConfigOrNull()?.let { bambuEntityIds(it, knownSnapshot) }.orEmpty()
    ENTITY_ID.findAll(raw).map { it.value }.toList() + bambu
  }
}

private val ENTITY_ID = Regex("""\b[a-z_][a-z0-9_]*\.[a-z0-9_]+\b""")

private fun String.toCardConfigOrNull(): CardConfig? = runCatching {
  val raw = Json.parseToJsonElement(this) as JsonObject
  val type = raw["type"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
  CardConfig(type = type, raw = raw)
}
  .getOrNull()

/** [EntityValue] for an entity the watch read itself: as the phone builds it for the watch. */
internal fun EntityState.toWearEntityValue(snapshot: HaSnapshot): EntityValue {
  val attrs = attributes
  fun text(key: String) = (attrs[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
  return EntityValue(
    state = state,
    friendlyName = text("friendly_name"),
    unit = text("unit_of_measurement"),
    deviceClass = text("device_class"),
    entityPicture = text("entity_picture"),
    displayPrecision = displayPrecisionOf(this, snapshot),
    attributes =
      WEAR_EXTRA_ATTRIBUTES.mapNotNull { key ->
          (attrs[key] as? JsonPrimitive)?.contentOrNull?.let { key to it }
        }
        .toMap() +
        (attrs["options"] as? JsonArray)?.let { mapOf("input_options" to it.toString()) }.orEmpty(),
  )
}

/** Asks every connected phone for a token ([WearAccessRequest]). */
private suspend fun sendAccessRequest(context: Context) {
  val nodes = Wearable.getNodeClient(context).connectedNodes.await()
  val bytes = encodeProto(WearAccessRequest(sentAtMs = System.currentTimeMillis()))
  val messages = Wearable.getMessageClient(context)
  for (node in nodes) {
    messages.sendMessage(node.id, WearSyncPaths.ACCESS_REQUEST_MESSAGE, bytes).await()
  }
}

/**
 * Keeps a [WearAccess] the phone sent ([bytes]) if it is for the instance the watch is signed in
 * to.
 */
internal fun acceptAccess(context: Context, bytes: ByteArray): Boolean {
  val access = decodeProto<WearAccess>(bytes) ?: return false
  return WearDirectAccess(context).accept(access, WearOfflineStore(context).readSettings())
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
  kotlinx.coroutines.suspendCancellableCoroutine { cont ->
    addOnSuccessListener { value -> cont.resumeWith(Result.success(value)) }
    addOnFailureListener { error -> cont.resumeWith(Result.failure(error)) }
  }
