package ee.schimke.terrazzo.assistant

import ee.schimke.ha.model.EntityState
import kotlinx.serialization.json.JsonPrimitive

/**
 * The entity rules the AppFunctions service applies, kept free of Android and AppFunctions types so
 * they are unit-tested on the JVM.
 */
internal object AssistantEntities {

  /** How many entities one search returns; an agent narrows with a query or domain instead. */
  const val SEARCH_LIMIT = 25

  /**
   * Domains an agent may read but never switch. A lock or an alarm panel guards the house, so it
   * stays a deliberate act in Home Assistant or on a card, never an inferred one.
   */
  val READ_ONLY_DOMAINS = setOf("lock", "alarm_control_panel")

  fun domain(entityId: String): String = entityId.substringBefore('.', missingDelimiterValue = "")

  /** The name Home Assistant shows: the `friendly_name` attribute, else the entity id. */
  fun name(entity: EntityState): String =
    (entity.attributes["friendly_name"] as? JsonPrimitive)
      ?.takeIf { it.isString }
      ?.content
      ?.takeIf { it.isNotBlank() } ?: entity.entityId

  fun unit(entity: EntityState): String? =
    (entity.attributes["unit_of_measurement"] as? JsonPrimitive)
      ?.takeIf { it.isString }
      ?.content
      ?.takeIf { it.isNotBlank() }

  /**
   * The entities matching [query] (every word in the name or entity id, ignoring case) in [domain],
   * best first: an exact name or id, then names starting with the query, then the rest by name.
   * Blank arguments don't filter.
   */
  fun search(
    states: Collection<EntityState>,
    query: String?,
    domain: String?,
    limit: Int = SEARCH_LIMIT,
  ): List<EntityState> {
    val wanted = query?.trim()?.lowercase().orEmpty()
    val words = wanted.split(' ').filter { it.isNotEmpty() }
    val inDomain = domain?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    return states
      .asSequence()
      .filter { inDomain == null || domain(it.entityId) == inDomain }
      .filter { entity ->
        val haystack = "${name(entity).lowercase()} ${entity.entityId.lowercase()}"
        words.all { it in haystack }
      }
      .sortedWith(
        compareBy<EntityState>(
            { entity ->
              val name = name(entity).lowercase()
              when {
                wanted.isEmpty() -> 2
                name == wanted || entity.entityId.lowercase() == wanted -> 0
                name.startsWith(wanted) -> 1
                else -> 2
              }
            },
            { name(it).lowercase() },
          )
          .thenBy { it.entityId }
      )
      .take(limit)
      .toList()
  }

  /** Why an agent may not switch [entityId], or null when it may. */
  fun writeRefusal(entityId: String): String? =
    when (domain(entityId)) {
      in READ_ONLY_DOMAINS ->
        "$entityId is a ${domain(entityId)}; Terrazzo doesn't let assistants change it"
      else -> null
    }
}
