package ee.schimke.ha.design

import kotlinx.serialization.Serializable

/**
 * What Home Assistant knows about the device behind an entity: the integration that provides it,
 * the device's manufacturer and model, and the entity's translation key (`flow_temperature`). Any
 * field may be unknown, for instance when the user can't read the device registry.
 *
 * [displayPrecision] is the entity registry's display precision (the user's `display_precision`
 * sensor option, else the integration's `suggested_display_precision`): how many decimals
 * `formatState` shows. It isn't part of device matching.
 */
@Serializable
data class DeviceInfo(
  val integration: String? = null,
  val manufacturer: String? = null,
  val model: String? = null,
  val translationKey: String? = null,
  val displayPrecision: Int? = null,
)

/**
 * The devices a design is made for. A design without one is generic; with one, it is used only for
 * cards whose entity's device matches every field it sets, ahead of the generic design for the same
 * key. Text compares without case; [model] may use `*` as a wildcard (`ecoTEC*`).
 */
@Serializable
data class DeviceMatch(
  val integration: String? = null,
  val manufacturer: String? = null,
  val model: String? = null,
  val translationKey: String? = null,
) {
  /** How many fields it sets: between two matching designs, the higher one is more specific. */
  val specificity: Int
    get() = listOfNotNull(integration, manufacturer, model, translationKey).size

  /**
   * This match in its canonical form: trimmed and lowercase, blank fields unset. Matching ignores
   * case, so two matches that differ only in case are the same match once normalized.
   */
  fun normalized(): DeviceMatch {
    fun norm(value: String?) = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    return DeviceMatch(norm(integration), norm(manufacturer), norm(model), norm(translationKey))
  }

  /** Whether [device] satisfies every field this sets. An empty match matches nothing. */
  fun matches(device: DeviceInfo): Boolean =
    specificity > 0 &&
      same(integration, device.integration) &&
      same(manufacturer, device.manufacturer) &&
      (model == null || device.model?.let { glob(model).matches(it) } == true) &&
      same(translationKey, device.translationKey)

  private fun same(wanted: String?, actual: String?) =
    wanted == null || (actual != null && wanted.equals(actual, ignoreCase = true))

  private fun glob(pattern: String): Regex =
    Regex(
      pattern.split('*').joinToString(".*") { Regex.escape(it) },
      RegexOption.IGNORE_CASE,
    )

  companion object {
    /**
     * The design to use among [candidates] (each a design's device match, or null for generic) for
     * [device]: the most specific that matches, else the generic one, else null.
     */
    fun <T> pick(candidates: List<Pair<DeviceMatch?, T>>, device: DeviceInfo?): T? {
      val specific = device?.let { d ->
        candidates
          .filter { (match, _) -> match != null && match.matches(d) }
          .maxByOrNull { (match, _) -> match!!.specificity }
      }
      return (specific ?: candidates.firstOrNull { it.first == null })?.second
    }
  }
}
