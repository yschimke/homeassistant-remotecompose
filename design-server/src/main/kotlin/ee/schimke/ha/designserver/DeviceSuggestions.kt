package ee.schimke.ha.designserver

import ee.schimke.ha.design.DesignEntry
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.design.DesignSurface
import ee.schimke.ha.design.DeviceMatch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The designs a dashboard editor may suggest for an entity: those made for a device
 * ([DesignEntry.device]), one row per design, card type, entity domain and device match, without
 * blobs or keys. [Suggestion.domain] is the domain the design's entity slot was made for (its key's
 * `domain` property): a printer's light design is no suggestion for the printer's sensors, since
 * the resolver could not draw them with it.
 *
 * Home Assistant's card picker asks a custom card for suggestions synchronously, entity by entity
 * (`getEntitySuggestion`), so the Remote Compose card matches the entity's device in the browser
 * against this index, from the registry facts Home Assistant already gave it. No device facts leave
 * Home Assistant; the chosen design goes back to the resolver by [Suggestion.designId] only.
 *
 * Generic designs are left out on purpose: Home Assistant's own cards are the right suggestion for
 * an entity with nothing made for its device, so the index stays small and the picker quiet.
 */
object DeviceSuggestions {
  const val PATH = "suggestions.json"

  @Serializable
  data class Suggestion(
    val designId: String,
    val cardType: String,
    val device: DeviceMatch,
    val title: String? = null,
    /** The entity domain the design draws (`light`), or null when its key doesn't say. */
    val domain: String? = null,
    /** Contract semantic input variable names a multi-entity pack reads (for `bindings:`). */
    val inputs: List<String>? = null,
    /** Contract semantic button action names a multi-entity pack fires (for `actions:`). */
    val actions: List<String>? = null,
  )

  @Serializable data class Index(val serial: Long, val designs: List<Suggestion>)

  /**
   * [entries] as the index: live phone-card entries made for a device and chosen by a stable id,
   * each with the contract its template declares ([contract], from [namedValues]: the names an
   * entry's template binds, or null when it can't be read).
   */
  fun index(
    serial: Long,
    entries: List<DesignEntry>,
    namedValues: (DesignEntry) -> List<String>? = { null },
  ): Index =
    Index(
      serial = serial,
      designs =
        entries
          .asSequence()
          .filter { !it.revoked && it.key.surface == DesignSurface.MobileApp }
          .mapNotNull { entry ->
            val id = entry.stableDesignId ?: return@mapNotNull null
            val device = entry.device?.normalized()?.takeIf { it.specificity > 0 }
            val domain = runCatching {
              DesignProperties.parse(entry.key.shape)["domain"]
            }
              .getOrNull()
            device?.let {
              val (inputs, actions) = contract(namedValues(entry).orEmpty())
              Suggestion(
                id,
                entry.key.cardType,
                it,
                entry.title,
                domain,
                inputs = inputs.ifEmpty { null },
                actions = actions.ifEmpty { null },
              )
            }
          }
          .distinct()
          .sortedWith(
            compareByDescending<Suggestion> { it.device.rank }
              .thenBy { it.designId }
              .thenBy { it.cardType }
              .thenBy { it.domain.orEmpty() },
          )
          .toList(),
    )

  /**
   * A template's contract from the names it binds ([names], `USER:` already taken off), as the
   * app's semantic bindings read them (`SemanticDeviceBindings.kt`): its **actions** are the
   * buttons named by `action_<name>` / `action.<name>` and their `_visible` / `.visible` switches;
   * its **inputs** every other name a card's `bindings:` may fill, leaving out what the host pushes
   * itself (a slot's `<domain>.s<n>.…`, `host.`, `meta.`, `HA:`, `card.title`).
   */
  fun contract(names: List<String>): Pair<List<String>, List<String>> {
    val actions = sortedSetOf<String>()
    val inputs = sortedSetOf<String>()
    for (raw in names) {
      val name = raw.removePrefix("USER:").trim()
      if (name.isEmpty()) continue
      val action = ACTION.matchEntire(name)?.groupValues?.get(1)
      when {
        action != null -> actions += action
        HOST_PUSHED.any { name.startsWith(it) } ||
          name == "card.title" ||
          SLOT.containsMatchIn(name) -> Unit
        else -> inputs += name
      }
    }
    return inputs.toList() to actions.toList()
  }

  /**
   * The names [template] binds that a card's `bindings:` can fill, for [contract]: not its named
   * colors, which the app never binds from an entity; null for a template that can't be read.
   */
  fun namedValues(template: DecodedTemplate): List<String>? =
    template.takeIf { it.error == null }?.bindings?.filter { it.type != "color" }?.map { it.name }

  private val ACTION = Regex("""^action[_.](.+?)(?:[_.]visible)?$""")
  private val SLOT = Regex("""^[a-z0-9_]+\.s\d+\.""")
  private val HOST_PUSHED = listOf("host.", "meta.", "HA:")

  fun encode(index: Index): String = json.encodeToString(Index.serializer(), index)

  private val json = Json { explicitNulls = false }
}
