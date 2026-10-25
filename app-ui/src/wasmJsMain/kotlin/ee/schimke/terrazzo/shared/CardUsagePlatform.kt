@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.terrazzo.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import ee.schimke.ha.client.design.UsageLedger
import ee.schimke.ha.client.design.UsageStore
import ee.schimke.ha.client.design.UsageSurfaces
import kotlin.js.JsString
import kotlin.js.Promise
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The web app selects per-install Wasmo settings or the existing localStorage path at startup. A
 * browser won't send a page's own User-Agent, so the app's release tag travels in the report body
 * and the blob URL instead.
 */
internal actual fun platformCardUsage(): PlatformCardUsage? =
  PlatformCardUsage(
    store = AppStorageUsageStore,
    surface = UsageSurfaces.WEB,
    platform = "web",
    sendUserAgentHeader = false,
    readSharing = { browserAppStorage.get(SHARING_KEY) },
    writeSharing = { browserAppStorage.set(SHARING_KEY, it) },
  )

private const val SHARING_KEY = "terrazzo.usage.sharing"
private const val LEDGER_KEY = "terrazzo.usage.ledger"

internal var browserAppStorage: AppStorage =
  LocalAppStorage(::storageGet, ::storageSet, ::storageRemove)
  private set

private val storageScope = MainScope()

internal suspend fun initializeBrowserAppStorage() {
  if (backendStorageKind().await().toString() != "wasmo") return
  browserAppStorage =
    WasmoAppStorage.create(
      BrowserStorageTransport,
      storageScope,
      browserAppStorage,
      setOf(SHARING_KEY, LEDGER_KEY, CARD_STYLE_KEY),
    )
}

@Composable
internal fun browserAppStorageLabel(): String {
  val storage =
    browserAppStorage as? WasmoAppStorage ?: return "Usage settings stored in this browser."
  return when (storage.syncState.collectAsState().value) {
    StorageSyncState.SAVED -> "Usage settings stored on your Wasmo."
    StorageSyncState.SAVING -> "Saving usage settings to your Wasmo…"
    StorageSyncState.FAILED ->
      "Could not save usage settings. Kept in this tab; change the sharing choice to retry."
  }
}

private object BrowserStorageTransport : AppStorageTransport {
  private fun decode(value: String): Map<String, String> =
    Json.parseToJsonElement(value).jsonObject.mapValues { it.value.jsonPrimitive.content }

  override suspend fun read(): Map<String, String> = decode(readSettings().await().toString())

  override suspend fun importIfEmpty(values: Map<String, String>): Map<String, String> {
    val json = JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString()
    return decode(importSettings(json).await().toString())
  }

  override suspend fun write(key: String, value: String?) {
    writeSettings(key, value).await()
  }
}

@JsFun(
  "() => window.terrazzoBackend.ready.then(b => b.kind === 'wasmo' && b.caps.kv === true ? 'wasmo' : 'local')"
)
private external fun backendStorageKind(): Promise<JsString>

@JsFun("() => window.terrazzoBackend.settings.read()")
private external fun readSettings(): Promise<JsString>

@JsFun("(values) => window.terrazzoBackend.settings.importIfEmpty(values)")
private external fun importSettings(values: String): Promise<JsString>

@JsFun("(key, value) => window.terrazzoBackend.settings.write(key, value).then(() => '')")
private external fun writeSettings(key: String, value: String?): Promise<JsString>

private object AppStorageUsageStore : UsageStore {
  private val json = Json { ignoreUnknownKeys = true }

  override suspend fun read(): UsageLedger? =
    browserAppStorage.get(LEDGER_KEY)?.let {
      runCatching { json.decodeFromString(UsageLedger.serializer(), it) }.getOrNull()
    }

  override suspend fun write(ledger: UsageLedger) {
    browserAppStorage.set(LEDGER_KEY, json.encodeToString(UsageLedger.serializer(), ledger))
  }

  override suspend fun delete() {
    browserAppStorage.set(LEDGER_KEY, null)
  }
}

// Storage can be unavailable (a sandboxed frame, privacy settings): then nothing is kept, so
// nothing is counted across reloads and the choice reads as never made.
@JsFun("(key) => { try { return window.localStorage.getItem(key); } catch (e) { return null; } }")
private external fun storageGet(key: String): String?

@JsFun("(key, value) => { try { window.localStorage.setItem(key, value); } catch (e) {} }")
private external fun storageSet(key: String, value: String)

@JsFun("(key) => { try { window.localStorage.removeItem(key); } catch (e) {} }")
private external fun storageRemove(key: String)
