@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.terrazzo.shared

// Local, not session, storage: a reload or a new tab picks a running agent session back up. The
// capabilities expire with their sessions (a day at most) and are dropped once a session ends.
@JsFun("(key) => { try { return localStorage.getItem(key); } catch (_) { return null; } }")
internal actual external fun readAgentGrants(key: String): String?

@JsFun(
  "(key, value) => { try { if (value == null) localStorage.removeItem(key); else localStorage.setItem(key, value); } catch (_) {} }"
)
internal actual external fun writeAgentGrants(key: String, value: String?)
