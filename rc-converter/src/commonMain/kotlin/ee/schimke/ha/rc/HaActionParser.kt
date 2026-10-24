package ee.schimke.ha.rc

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.rc.components.HaAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parse HA's Lovelace `tap_action:` / `hold_action:` object into a typed [HaAction]. Schema
 * reference: https://www.home-assistant.io/dashboards/actions/
 *
 * Implemented: call-service / perform-action, toggle, more-info, navigate, url, none. Not yet:
 * assist, fire-dom-event.
 */
internal fun parseHaAction(cfg: JsonObject?, defaultEntityId: String?): HaAction {
  if (cfg == null) return HaAction.None
  val action = cfg["action"]?.jsonPrimitive?.content ?: return HaAction.None
  return when (action) {
    "toggle" ->
      HaAction.Toggle(
        cfg["entity"]?.jsonPrimitive?.content ?: defaultEntityId ?: return HaAction.None
      )
    "more-info" ->
      HaAction.MoreInfo(
        cfg["entity"]?.jsonPrimitive?.content ?: defaultEntityId ?: return HaAction.None
      )
    "navigate" ->
      cfg["navigation_path"]?.jsonPrimitive?.content?.let(HaAction::Navigate) ?: HaAction.None
    "url" -> cfg["url_path"]?.jsonPrimitive?.content?.let(HaAction::Url) ?: HaAction.None
    "call-service",
    "perform-action" -> {
      val svc =
        (cfg["service"] ?: cfg["perform_action"])?.jsonPrimitive?.content ?: return HaAction.None
      val (domain, service) =
        svc.split('.', limit = 2).takeIf { it.size == 2 } ?: return HaAction.None
      val target = cfg["target"] as? JsonObject
      val legacyData = cfg["service_data"] as? JsonObject
      val data = cfg["data"] as? JsonObject ?: legacyData ?: JsonObject(emptyMap())
      // HA accepts a single id or a list for entity_id, and area/device/label targets too.
      val ids = target?.get("entity_id") ?: legacyData?.get("entity_id")
      val singleId =
        when (ids) {
          is JsonPrimitive -> ids.contentOrNull
          is JsonArray -> (ids.singleOrNull() as? JsonPrimitive)?.contentOrNull
          else -> null
        }
      val otherTargets = target.orEmpty().filterKeys { it != "entity_id" }
      when {
        ids == null && otherTargets.isEmpty() ->
          HaAction.CallService(domain, service, defaultEntityId, data)
        singleId != null && otherTargets.isEmpty() ->
          HaAction.CallService(domain, service, singleId, data)
        // Several entities, or an area/device target: there's no single entity to target, so carry
        // the whole target in the service data, where HA reads it too, and never fall back to the
        // card's own entity.
        else -> HaAction.CallService(domain, service, null, JsonObject(data + target.orEmpty()))
      }
    }
    "none" -> HaAction.None
    else -> HaAction.None
  }
}

/**
 * The domains a default tap toggles: `home-assistant/frontend` `src/common/const.ts`
 * `DOMAINS_TOGGLE`, verbatim. Cover, lock and media player are deliberately absent: a tap on a
 * garage door or a front-door lock opens its more-info dialog, never actuates it.
 */
internal val DOMAINS_TOGGLE: Set<String> =
  setOf("fan", "input_boolean", "light", "switch", "group", "automation", "humidifier", "valve")

/**
 * HA's toggle-or-more-info default: a [DOMAINS_TOGGLE] entity toggles, anything else opens
 * more-info. This is `hui-button-card`'s `getEntityDefaultButtonAction`, a `picture-glance` entity
 * icon's default and the tile icon's (`getEntityDefaultTileIconAction`). Every card without a
 * `tap_action` takes it: HA's entity, glance, gauge and picture cards fall back to more-info even
 * for a light, but more-info isn't a view in every host here, so they keep the toggle.
 */
internal fun defaultTapActionFor(entityId: String?): HaAction {
  val domain = entityId?.substringBefore('.') ?: return HaAction.None
  return if (domain in DOMAINS_TOGGLE) HaAction.Toggle(entityId) else HaAction.MoreInfo(entityId)
}

/**
 * The default tap of [card] (or one of its rows) on [entityId], when there is no `tap_action`:
 * [defaultTapActionFor], except that a `picture-glance` with `force_dialog: true` opens more-info
 * for every entity, as HA's does.
 */
internal fun defaultTapActionFor(card: CardConfig, entityId: String?): HaAction {
  val forceDialog =
    card.type == "picture-glance" &&
      (card.raw["force_dialog"] as? JsonPrimitive)?.booleanOrNull == true
  return if (forceDialog) entityId?.let(HaAction::MoreInfo) ?: HaAction.None
  else defaultTapActionFor(entityId)
}
