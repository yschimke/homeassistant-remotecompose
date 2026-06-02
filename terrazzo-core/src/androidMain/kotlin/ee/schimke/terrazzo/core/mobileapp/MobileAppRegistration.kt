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
)

/** Wire-format response body. */
@Serializable
internal data class RegistrationResponse(
  @SerialName("webhook_id") val webhookId: String,
  val secret: String? = null,
  @SerialName("cloudhook_url") val cloudhookUrl: String? = null,
  @SerialName("remote_ui_url") val remoteUiUrl: String? = null,
)
