@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.terrazzo.shared

import ee.schimke.ha.client.design.UsageLedger
import ee.schimke.ha.client.design.UsageStore
import ee.schimke.ha.client.design.UsageSurfaces
import kotlinx.serialization.json.Json

/**
 * The web app keeps its usage ledger and setting in the page origin's `localStorage` (the Home
 * Assistant panel's, or the hosted site's). A browser won't send a page's own User-Agent, so the
 * app's release tag travels in the report body and the blob URL instead.
 */
internal actual fun platformCardUsage(): PlatformCardUsage? =
  PlatformCardUsage(
    store = LocalStorageUsageStore,
    surface = UsageSurfaces.WEB,
    platform = "web",
    sendUserAgentHeader = false,
    readSharing = { storageGet(SHARING_KEY) },
    writeSharing = { storageSet(SHARING_KEY, it) },
  )

private const val SHARING_KEY = "terrazzo.usage.sharing"
private const val LEDGER_KEY = "terrazzo.usage.ledger"

private object LocalStorageUsageStore : UsageStore {
  private val json = Json { ignoreUnknownKeys = true }

  override suspend fun read(): UsageLedger? =
    storageGet(LEDGER_KEY)?.let {
      runCatching { json.decodeFromString(UsageLedger.serializer(), it) }.getOrNull()
    }

  override suspend fun write(ledger: UsageLedger) {
    storageSet(LEDGER_KEY, json.encodeToString(UsageLedger.serializer(), ledger))
  }

  override suspend fun delete() {
    storageRemove(LEDGER_KEY)
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
