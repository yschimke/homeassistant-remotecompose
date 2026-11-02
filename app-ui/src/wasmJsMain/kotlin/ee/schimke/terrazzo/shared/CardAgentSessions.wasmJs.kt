@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.terrazzo.shared

@JsFun(
  "() => { try { return sessionStorage.getItem('terrazzo.cardAgentSessions'); } catch (_) { return null; } }"
)
internal actual external fun readCardAgentSessions(): String?

@JsFun(
  "(value) => { try { if (value == null) sessionStorage.removeItem('terrazzo.cardAgentSessions'); else sessionStorage.setItem('terrazzo.cardAgentSessions', value); } catch (_) {} }"
)
internal actual external fun writeCardAgentSessions(value: String?)
