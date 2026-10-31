package ee.schimke.terrazzo.quest

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse

/**
 * Home Assistant OAuth for Quest. This intentionally lives outside `:terrazzo-core`: that module
 * has API-35-only Remote Compose features, while Horizon OS Quest builds must stay on API 34.
 */
class QuestAuth(context: Context) : AutoCloseable {
  private val service = AuthorizationService(context.applicationContext)

  fun authorizationIntent(baseUrl: String): Intent {
    val request =
      AuthorizationRequest.Builder(
          configuration(baseUrl),
          CLIENT_ID,
          ResponseTypeValues.CODE,
          Uri.parse(REDIRECT_URI),
        )
        .build()
    return service.getAuthorizationRequestIntent(request)
  }

  suspend fun exchangeResult(data: Intent?): QuestTokens {
    val failure = data?.let(AuthorizationException::fromIntent)
    if (failure != null) throw failure
    val response =
      data?.let(AuthorizationResponse::fromIntent)
        ?: error("Home Assistant sign-in returned no authorization response")
    return response.createTokenExchangeRequest().perform()
  }

  suspend fun refresh(baseUrl: String, refreshToken: String): QuestTokens =
    TokenRequest.Builder(configuration(baseUrl), CLIENT_ID)
      .setGrantType("refresh_token")
      .setRefreshToken(refreshToken)
      .build()
      .perform()

  private suspend fun TokenRequest.perform(): QuestTokens =
    suspendCancellableCoroutine { continuation ->
      service.performTokenRequest(this) { response, failure ->
        when {
          response != null -> continuation.resume(response.toQuestTokens())
          failure != null -> continuation.resumeWithException(failure)
          else ->
            continuation.resumeWithException(AuthorizationException.GeneralErrors.NETWORK_ERROR)
        }
      }
    }

  private fun TokenResponse.toQuestTokens(): QuestTokens {
    val access = accessToken ?: error("Home Assistant returned no access token")
    return QuestTokens(access, refreshToken)
  }

  override fun close() = service.dispose()

  private fun configuration(baseUrl: String): AuthorizationServiceConfiguration {
    val origin = baseUrl.trim().removeSuffix("/")
    require(origin.startsWith("https://") || origin.startsWith("http://")) {
      "Enter a full Home Assistant URL, including https://"
    }
    return AuthorizationServiceConfiguration(
      Uri.parse("$origin/auth/authorize"),
      Uri.parse("$origin/auth/token"),
    )
  }

  companion object {
    const val CLIENT_ID =
      "https://yschimke.github.io/homeassistant-remotecompose/terrazzo-auth/index.html"
    const val REDIRECT_URI = "rcha://auth-callback"
  }
}

data class QuestTokens(val accessToken: String, val refreshToken: String?)

/** Refresh credentials encrypted with an Android Keystore AES-GCM key, one Quest installation. */
class QuestTokenStore(context: Context) {
  private val prefs =
    context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

  fun load(): QuestCredential? {
    val baseUrl = prefs.getString(BASE_URL, null) ?: return null
    val token = runCatching {
      val iv = prefs.getString(IV, null)?.decode() ?: return null
      val ciphertext = prefs.getString(CIPHERTEXT, null)?.decode() ?: return null
      decrypt(iv, ciphertext).decodeToString()
    }
      .getOrElse {
        clear()
        return null
      }
    return QuestCredential(baseUrl, token)
  }

  fun save(baseUrl: String, refreshToken: String) {
    val (iv, ciphertext) = encrypt(refreshToken.encodeToByteArray())
    prefs
      .edit()
      .putString(BASE_URL, baseUrl.trim().removeSuffix("/"))
      .putString(IV, iv.encode())
      .putString(CIPHERTEXT, ciphertext.encode())
      .apply()
  }

  fun clear() = prefs.edit().clear().apply()

  private fun encrypt(value: ByteArray): Pair<ByteArray, ByteArray> {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key())
    return cipher.iv to cipher.doFinal(value)
  }

  private fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
    return cipher.doFinal(ciphertext)
  }

  private fun key(): SecretKey {
    val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let {
      return it
    }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
      .apply {
        init(
          KeyGenParameterSpec.Builder(
              KEY_ALIAS,
              KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        )
      }
      .generateKey()
  }

  private fun String.decode(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

  private fun ByteArray.encode(): String = Base64.encodeToString(this, Base64.NO_WRAP)

  private companion object {
    const val PREFERENCES = "quest_credentials"
    const val BASE_URL = "base_url"
    const val IV = "iv"
    const val CIPHERTEXT = "ciphertext"
    const val ANDROID_KEY_STORE = "AndroidKeyStore"
    const val KEY_ALIAS = "terrazzo_quest_refresh_token"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val TAG_LENGTH_BITS = 128
  }
}

data class QuestCredential(val baseUrl: String, val refreshToken: String)
