package ee.schimke.ha.model

/**
 * Which kind of surface a rendered card is destined for. Generators read this to pick layout
 * variants (compact for wear, focusable for tv, etc.); the player passes its own profile when
 * fetching from a server, so the server can return a profile-tuned `.rc` document.
 *
 * Kept here in `ha-model` rather than in either side because the value has to round-trip across
 * client ↔ add-on as a query parameter — both ends must agree on the spelling.
 */
enum class ClientProfile {
  /** Phone / tablet — the default. Material3, full Lovelace fidelity. */
  Phone,
  /** Wear OS launcher tile / app — compact, AOD-safe, no shadows. */
  Wear,
  /** Android TV — large typography, focusable affordances, 10-ft margins. */
  Tv,
  /** Glance launcher widget — single-card, no nested players. */
  Glance,
  /** 1-bit colour for e-paper / ESP32. */
  Mono;

  /** Wire form, lower-case. Kept stable; do not localise. */
  val wire: String
    get() = name.lowercase()

  companion object {
    fun parse(s: String?): ClientProfile? = s?.let { value ->
      entries.firstOrNull { it.wire == value.lowercase() }
    }
  }
}
