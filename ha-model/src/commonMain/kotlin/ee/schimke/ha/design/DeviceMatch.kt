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
 *
 * [deviceId] is the registry id of that one device (what a card config such as the Bambu Lab cards'
 * `printer:` names). It says which device, not what kind, so design matching ignores it.
 *
 * [areaId] is the room the entity is in: its own area, else its device's, as Home Assistant's
 * frontend resolves it. [entityCategory] (`config`, `diagnostic`) and [hidden] mark entities the
 * frontend leaves off auto-generated views; the room controls do the same. None of the three is
 * part of device matching.
 */
@Serializable
data class DeviceInfo(
  val integration: String? = null,
  val manufacturer: String? = null,
  val model: String? = null,
  val translationKey: String? = null,
  val displayPrecision: Int? = null,
  val deviceId: String? = null,
  val areaId: String? = null,
  val entityCategory: String? = null,
  val hidden: Boolean = false,
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
  /** How many fields it sets; zero for a match that matches nothing. */
  val specificity: Int
    get() = listOfNotNull(integration, manufacturer, model, translationKey).size

  /**
   * Who the design is for, from the narrowest field it sets: a [DeviceScope.Model] design beats a
   * [DeviceScope.Brand] one however many fields the brand design sets. Null for an empty match.
   */
  val scope: DeviceScope?
    get() =
      when {
        !model.isNullOrBlank() -> DeviceScope.Model
        !integration.isNullOrBlank() || !manufacturer.isNullOrBlank() -> DeviceScope.Brand
        !translationKey.isNullOrBlank() -> DeviceScope.Role
        else -> null
      }

  /**
   * How designs that match one device rank: by [scope], narrowest first, then by [specificity]
   * within a scope (a brand's chamber-light design over the brand's general one). Zero for an empty
   * match, below every match that sets a field.
   */
  val rank: Int
    get() = scope?.let { (DeviceScope.entries.size - it.ordinal) * RANK_STEP + specificity } ?: 0

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
     * [device]: the best-ranked ([rank]) that matches, else the generic one, else null.
     */
    fun <T> pick(candidates: List<Pair<DeviceMatch?, T>>, device: DeviceInfo?): T? {
      val specific = device?.let { d ->
        candidates
          .filter { (match, _) -> match != null && match.matches(d) }
          .maxByOrNull { (match, _) -> match!!.rank }
      }
      return (specific ?: candidates.firstOrNull { it.first == null })?.second
    }
  }
}

/**
 * How wide a device design's audience is, narrowest first. A design for one model is the better fit
 * than one for its brand, and a brand's than one for a role (`flow_temperature`) on any device. One
 * particular device is not a scope: its registry id belongs to one home, so a design for it is
 * chosen per card instead of published for everyone.
 */
enum class DeviceScope {
  /** A model or model family (`model`, a glob). */
  Model,
  /** Every device of an integration or manufacturer. */
  Brand,
  /** An entity role on any device (`translationKey` alone). */
  Role,
}

/** More than any [DeviceMatch.specificity], so scope always decides before field count. */
private const val RANK_STEP = 8
