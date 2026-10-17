package ee.schimke.ha.addon.bridge

import ee.schimke.ha.model.EntityState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Latest [EntityState] per `entity_id`, mirroring what HA's frontend holds in `hass.states`. The
 * whole map is wrapped in a [StateFlow] so routes can publish read-only views; updates from the
 * bridge mutate the underlying map under a structural-equality check that keeps the flow from
 * emitting on no-op writes.
 */
class StateCache {
  private val _states = MutableStateFlow<Map<String, EntityState>>(emptyMap())
  val states: StateFlow<Map<String, EntityState>> = _states.asStateFlow()

  fun get(entityId: String): EntityState? = _states.value[entityId]

  fun replaceAll(map: Map<String, EntityState>) {
    _states.value = map.toMap()
  }

  fun put(entityId: String, value: EntityState) {
    val current = _states.value
    if (current[entityId] == value) return
    _states.value = current + (entityId to value)
  }

  /**
   * The entity registry's display precision by `entity_id` (`dp` in
   * `config/entity_registry/list_for_display`): what the app's `formatState(entity, snapshot)`
   * rounds to, so the add-on's `<id>.state` push matches the document the app authored.
   */
  @Volatile private var precisions: Map<String, Int> = emptyMap()

  fun displayPrecision(entityId: String): Int? = precisions[entityId]

  fun replaceDisplayPrecisions(map: Map<String, Int>) {
    precisions = map.toMap()
  }

  fun remove(entityId: String) {
    val current = _states.value
    if (entityId !in current) return
    _states.value = current - entityId
  }
}
