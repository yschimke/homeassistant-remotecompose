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
  /** The FCM token HA holds for this install's relay push ([PushTarget]); null when none. */
  val pushToken: String? = null,
)

/**
 * Where HA sends a notification while the app holds no websocket: `app_data.push_url`, the Terrazzo
 * push relay, which forwards it to [token] (this install's FCM token) as a data message.
 */
data class PushTarget(val url: String, val token: String)

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
 * websocket's `mobile_app/push_notification_channel` while the app holds one open. With [pushUrl]
 * and [pushToken] (a [PushTarget]) HA falls back to POSTing the notification to the Terrazzo push
 * relay when no websocket is open, or the app doesn't confirm one in time; without them (a build
 * with no Firebase) the websocket is the only path. Both or neither: HA requires the pair.
 */
@Serializable
internal data class AppData(
  @SerialName("push_websocket_channel") val pushWebsocketChannel: Boolean,
  @SerialName("push_url") val pushUrl: String? = null,
  @SerialName("push_token") val pushToken: String? = null,
) {
  companion object {
    fun of(push: PushTarget?) = AppData(true, push?.url, push?.token)
  }
}

/** `update_registration`'s data: HA replaces the registration's `app_data` with [appData]. */
@Serializable
internal data class UpdateRegistration(
  @SerialName("app_data") val appData: AppData,
  @SerialName("app_version") val appVersion: String,
  @SerialName("device_name") val deviceName: String,
  val manufacturer: String,
  val model: String,
  @SerialName("os_version") val osVersion: String,
)

/**
 * A `mobile_app` webhook call, its data sealed with the registration's secret ([SecretBox]). HA
 * requires `"encrypted": true`, so it has no default: a default value isn't encoded.
 */
@Serializable
internal data class EncryptedWebhook(
  val type: String,
  val encrypted: Boolean,
  @SerialName("encrypted_data") val encryptedData: String,
)

/** Wire-format response body. */
@Serializable
internal data class RegistrationResponse(
  @SerialName("webhook_id") val webhookId: String,
  val secret: String? = null,
  @SerialName("cloudhook_url") val cloudhookUrl: String? = null,
  @SerialName("remote_ui_url") val remoteUiUrl: String? = null,
)
