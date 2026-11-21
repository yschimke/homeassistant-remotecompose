package ee.schimke.ha.designserver

import ee.schimke.ha.design.DesignEntry
import ee.schimke.ha.design.DesignSurface
import ee.schimke.ha.design.DeviceMatch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The designs a dashboard editor may suggest for an entity: those made for a device
 * ([DesignEntry.device]), one row per design, card type and device match, without blobs or keys.
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
  )

  @Serializable data class Index(val serial: Long, val designs: List<Suggestion>)

  /**
   * [entries] as the index: live phone-card entries made for a device and chosen by a stable id.
   */
  fun index(serial: Long, entries: List<DesignEntry>): Index =
    Index(
      serial = serial,
      designs =
        entries
          .asSequence()
          .filter { !it.revoked && it.key.surface == DesignSurface.MobileApp }
          .mapNotNull { entry ->
            val id = entry.stableDesignId ?: return@mapNotNull null
            val device = entry.device?.normalized()?.takeIf { it.specificity > 0 }
            device?.let { Suggestion(id, entry.key.cardType, it, entry.title) }
          }
          .distinct()
          .sortedWith(
            compareByDescending<Suggestion> { it.device.specificity }
              .thenBy { it.designId }
              .thenBy { it.cardType },
          )
          .toList(),
    )

  fun encode(index: Index): String = json.encodeToString(Index.serializer(), index)

  private val json = Json { explicitNulls = false }
}
