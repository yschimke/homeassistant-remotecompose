package ee.schimke.terrazzo.core.mobileapp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Persisted result of a successful `POST /api/mobile_app/registrations`.
 *
 * Lives next to the HA refresh token (per `instanceId` = base URL) and is what lets HA target push
 * notifications and Companion-style services at this specific install: HA exposes a
 * `notify.mobile_app_<slug>` service derived from the registered `device_name`.
 *
 * `deviceId` is generated once per app install and reused on every subsequent registration call so
 * HA sees the same device across logins and token refreshes; the rest comes back from HA's
 * response.
 */
@Serializable
data class MobileAppRegistration(
  val deviceId: String,
  val deviceName: String,
  val webhookId: String,
  val secret: String? = null,
  val cloudhookUrl: String? = null,
  val remoteUiUrl: String? = null,
  /**
   * Whether HA was asked to deliver notifications over the websocket push channel. Registrations
   * from before it are re-made so `notify.mobile_app_<device>` has somewhere to send.
   */
  val pushChannel: Boolean = false,
)

/** Wire-format request body for `POST /api/mobile_app/registrations`. */
@Serializable
internal data class RegistrationRequest(
  @SerialName("device_id") val deviceId: String,
  @SerialName("app_id") val appId: String,
  @SerialName("app_name") val appName: String,
  @SerialName("app_version") val appVersion: String,
  @SerialName("device_name") val deviceName: String,
  val manufacturer: String,
  val model: String,
  @SerialName("os_name") val osName: String,
  @SerialName("os_version") val osVersion: String,
  @SerialName("supports_encryption") val supportsEncryption: Boolean,
  @SerialName("app_data") val appData: AppData,
)

/**
 * `app_data`: `push_websocket_channel` makes HA route `notify.mobile_app_<device>` over the
 * websocket's `mobile_app/push_notification_channel` while the app holds one open. HA's FCM relay
 * serves only the official companion apps, so this is the app's only push path.
 */
@Serializable
internal data class AppData(@SerialName("push_websocket_channel") val pushWebsocketChannel: Boolean)

/** Wire-format response body. */
@Serializable
internal data class RegistrationResponse(
  @SerialName("webhook_id") val webhookId: String,
  val secret: String? = null,
  @SerialName("cloudhook_url") val cloudhookUrl: String? = null,
  @SerialName("remote_ui_url") val remoteUiUrl: String? = null,
)
