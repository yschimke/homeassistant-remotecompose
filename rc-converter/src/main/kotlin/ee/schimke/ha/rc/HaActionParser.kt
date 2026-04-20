package ee.schimke.ha.rc

import ee.schimke.ha.rc.components.HaAction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parse HA's Lovelace `tap_action:` / `hold_action:` object into a typed
 * [HaAction]. Schema reference:
 *   https://www.home-assistant.io/dashboards/actions/
 *
 * Implemented: call-service, toggle, more-info, navigate, url, none.
 * Not yet: perform-action (renamed call-service), assist, fire-dom-event.
 */
internal fun parseHaAction(cfg: JsonObject?, defaultEntityId: String?): HaAction {
    if (cfg == null) return HaAction.None
    val action = cfg["action"]?.jsonPrimitive?.content ?: return HaAction.None
    return when (action) {
        "toggle" -> HaAction.Toggle(cfg["entity"]?.jsonPrimitive?.content ?: defaultEntityId ?: return HaAction.None)
        "more-info" -> HaAction.MoreInfo(cfg["entity"]?.jsonPrimitive?.content ?: defaultEntityId ?: return HaAction.None)
        "navigate" -> cfg["navigation_path"]?.jsonPrimitive?.content?.let(HaAction::Navigate) ?: HaAction.None
        "url" -> cfg["url_path"]?.jsonPrimitive?.content?.let(HaAction::Url) ?: HaAction.None
        "call-service", "perform-action" -> {
            val svc = (cfg["service"] ?: cfg["perform_action"])?.jsonPrimitive?.content ?: return HaAction.None
            val (domain, service) = svc.split('.', limit = 2).takeIf { it.size == 2 } ?: return HaAction.None
            val target = cfg["target"]?.jsonObject
            val entityId = target?.get("entity_id")?.jsonPrimitive?.content
                ?: cfg["service_data"]?.jsonObject?.get("entity_id")?.jsonPrimitive?.content
                ?: defaultEntityId
            val data = cfg["data"]?.jsonObject ?: cfg["service_data"]?.jsonObject ?: JsonObject(emptyMap())
            HaAction.CallService(domain, service, entityId, data)
        }
        "none" -> HaAction.None
        else -> HaAction.None
    }
}

/**
 * HA's default tap action per entity domain — mirrors
 * `home-assistant/frontend` `src/panels/lovelace/cards/hui-tile-card.ts`
 * default action resolution.
 */
internal fun defaultTapActionFor(entityId: String?): HaAction {
    val domain = entityId?.substringBefore('.') ?: return HaAction.None
    return when (domain) {
        "light", "switch", "input_boolean", "fan", "cover", "media_player", "lock" -> HaAction.Toggle(entityId)
        else -> HaAction.MoreInfo(entityId)
    }
}
