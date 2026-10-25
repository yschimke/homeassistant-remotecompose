package ee.schimke.terrazzo.core.mobileapp

import android.content.Context
import android.os.Build
import android.util.Base64
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import ee.schimke.terrazzo.core.di.AppScope
import ee.schimke.terrazzo.core.network.HttpEngineFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.security.SecureRandom
import kotlinx.serialization.json.Json

/**
 * Registers this install with HA's `mobile_app` integration so the device appears in *Settings →
 * Devices & Services → Mobile App* and HA exposes a `notify.mobile_app_<slug>` service for it.
 *
 * Called once per HA instance immediately after OAuth succeeds. The call is non-fatal: if HA's
 * mobile_app integration isn't enabled, or the request fails for any reason, login still completes
 * — the only thing degraded is notification targeting. Re-running the call later (e.g. from a
 * settings screen) is safe; HA creates a new device entry if the `device_id` is unknown and
 * otherwise tolerates the duplicate.
 *
 * The handshake is HTTPS form-POST with the user's just-minted access token as bearer auth. We
 * request `supports_encryption: true` so HA hands us back a NaCl shared secret; we persist it for
 * device→HA webhook traffic: [updatePush] seals `update_registration` with it. Push notifications
 * HA→device take HA's websocket push channel (`app_data.push_websocket_channel`) while the live
 * session holds one, else the Terrazzo integration's `push_url` ([PushTarget]), which holds them
 * and wakes the app silently.
 */
@SingleIn(AppScope::class)
@Inject
class MobileAppRegistrar(
  private val context: Context,
  private val store: MobileAppStore,
  httpEngineFactory: HttpEngineFactory,
) {

  private val http =
    HttpClient(httpEngineFactory.engine) {
      install(HttpTimeout) {
        requestTimeoutMillis = REQUEST_TIMEOUT_MS
        connectTimeoutMillis = CONNECT_TIMEOUT_MS
      }
    }
  private val json = Json { ignoreUnknownKeys = true }
  private val random = SecureRandom()

  /**
   * Register this device with `baseUrl`'s mobile_app integration. Returns the persisted record on
   * success; throws on transport or HTTP failures so the caller can decide whether to surface or
   * swallow the error.
   */
  suspend fun register(baseUrl: String, accessToken: String): MobileAppRegistration {
    val deviceId = store.getOrCreateDeviceId(baseUrl)
    val deviceName = deviceName()
    val request =
      RegistrationRequest(
        deviceId = deviceId,
        appId = context.packageName,
        appName = APP_NAME,
        appVersion = appVersion(),
        deviceName = deviceName,
        manufacturer = Build.MANUFACTURER ?: "",
        model = Build.MODEL ?: "",
        osName = "Android",
        osVersion = osVersion(),
        supportsEncryption = true,
        appData = AppData.of(null),
      )

    val resp =
      http.post(baseUrl.trimEnd('/') + "/api/mobile_app/registrations") {
        header(HttpHeaders.Authorization, "Bearer $accessToken")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(RegistrationRequest.serializer(), request))
      }
    if (!resp.status.isSuccess()) {
      error("mobile_app registration failed: ${resp.status}")
    }
    val parsed = json.decodeFromString(RegistrationResponse.serializer(), resp.bodyAsText())
    val record =
      MobileAppRegistration(
        deviceId = deviceId,
        deviceName = deviceName,
        webhookId = parsed.webhookId,
        secret = parsed.secret,
        cloudhookUrl = parsed.cloudhookUrl,
        remoteUiUrl = parsed.remoteUiUrl,
        pushChannel = true,
      )
    store.put(baseUrl, record)
    return record
  }

  /**
   * Hands HA a new [push] target (the Terrazzo integration's push URL) for [baseUrl]'s
   * registration, with `update_registration` on its webhook: no new device, no login. HA requires
   * the call sealed with the registration's secret ([SecretBox]) when it has one. Returns the
   * updated record, or null when there is no registration to update; throws on transport or HTTP
   * failures.
   */
  suspend fun updatePush(baseUrl: String, push: PushTarget): MobileAppRegistration? {
    val stored = store.get(baseUrl) ?: return null
    val data =
      UpdateRegistration(
        appData = AppData.of(push),
        appVersion = appVersion(),
        deviceName = stored.deviceName,
        manufacturer = Build.MANUFACTURER ?: "",
        model = Build.MODEL ?: "",
        osVersion = osVersion(),
      )
    val plain = json.encodeToString(UpdateRegistration.serializer(), data)
    // A registration HA made without encryption (no secret) takes the call in the clear.
    val secret = stored.secret
    val body =
      if (secret == null) {
        """{"type":"update_registration","data":$plain}"""
      } else {
        val nonce = ByteArray(SecretBox.NONCE_SIZE).also { random.nextBytes(it) }
        val sealed = SecretBox.seal(SecretBox.keyFromHex(secret), nonce, plain.encodeToByteArray())
        json.encodeToString(
          EncryptedWebhook.serializer(),
          EncryptedWebhook(
            type = "update_registration",
            encrypted = true,
            encryptedData = Base64.encodeToString(sealed, Base64.NO_WRAP),
          ),
        )
      }
    val resp =
      http.post(baseUrl.trimEnd('/') + "/api/webhook/" + stored.webhookId) {
        contentType(ContentType.Application.Json)
        setBody(body)
      }
    if (!resp.status.isSuccess()) error("mobile_app update_registration failed: ${resp.status}")
    val record = stored.copy(pushUrl = push.url, pushToken = push.token)
    store.put(baseUrl, record)
    return record
  }

  private fun osVersion(): String = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString()

  private fun appVersion(): String =
    runCatching {
      @Suppress("DEPRECATION")
      context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }
      .getOrNull() ?: "0"

  private fun deviceName(): String {
    val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
    val model = Build.MODEL?.trim().orEmpty()
    return when {
      model.isEmpty() -> manufacturer.ifEmpty { "Android device" }
      manufacturer.isEmpty() || model.startsWith(manufacturer, ignoreCase = true) -> model
      else -> "$manufacturer $model"
    }
  }

  private companion object {
    const val APP_NAME = "Terrazzo"
    const val REQUEST_TIMEOUT_MS = 10_000L
    const val CONNECT_TIMEOUT_MS = 5_000L
  }
}
