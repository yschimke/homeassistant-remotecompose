package ee.schimke.ha.client

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray

/**
 * One `notify.mobile_app_<device>` message, as HA's local push channel
 * (`mobile_app/push_notification_channel`) delivers it. The `data` keys follow the HA companion
 * apps' notification options; the ones the app acts on are lifted out, the rest kept in [data].
 *
 * @property tag HA's `data.tag`: a later message with the same tag replaces this one, and `message:
 *   clear_notification` with it removes it.
 * @property entityId `data.entity_id`: the entity to show as a live card in the notification.
 * @property confirmId HA's `hass_confirm_id`, sent back with `mobile_app/push_notification_confirm`
 *   so HA knows the message landed.
 */
data class HaPushNotification(
  val message: String,
  val title: String? = null,
  val tag: String? = null,
  val group: String? = null,
  val channel: String? = null,
  val url: String? = null,
  val entityId: String? = null,
  val actions: List<Action> = emptyList(),
  val confirmId: String? = null,
  val data: JsonObject = JsonObject(emptyMap()),
) {
  /**
   * HA's `data.actions` entry: a button that fires `mobile_app_notification_action` or opens [uri].
   */
  data class Action(val action: String, val title: String, val uri: String? = null)

  /** `message: clear_notification` — remove the notification with [tag] rather than post. */
  val isClear: Boolean
    get() = message == CLEAR_MESSAGE

  /**
   * The id one message keeps on every device it reaches: HA's [tag] when it sets one, so a re-sent
   * message replaces the first and a dismissal anywhere dismisses it everywhere; otherwise derived
   * from the content, so the same message delivered twice (a reconnect replaying it) still posts
   * once.
   */
  val dedupeKey: String
    get() = tag?.let { "tag:$it" } ?: "msg:${(title.orEmpty() + "\u0000" + message).hashCode()}"

  companion object {
    const val CLEAR_MESSAGE = "clear_notification"

    /** Parses a push channel event; null when it carries no message. */
    fun parse(event: JsonObject): HaPushNotification? {
      val message = event.string("message") ?: return null
      val data = (event["data"] as? JsonObject) ?: JsonObject(emptyMap())
      val actions = runCatching {
        data["actions"]?.jsonArray
      }
        .getOrNull()
        .orEmpty()
        .mapNotNull { element ->
          val obj = element as? JsonObject ?: return@mapNotNull null
          val action = obj.string("action") ?: return@mapNotNull null
          Action(action, obj.string("title") ?: action, obj.string("uri"))
        }
      return HaPushNotification(
        message = message,
        title = event.string("title"),
        tag = data.string("tag"),
        group = data.string("group"),
        channel = data.string("channel"),
        url = data.string("clickAction") ?: data.string("url"),
        entityId = data.string("entity_id"),
        actions = actions,
        confirmId = event.string("hass_confirm_id"),
        data = data,
      )
    }

    private fun JsonObject.string(key: String): String? =
      (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
  }
}
