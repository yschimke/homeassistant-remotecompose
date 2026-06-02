package ee.schimke.terrazzo.core.mobileapp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import ee.schimke.terrazzo.core.di.AppScope
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * Persists the [MobileAppRegistration] returned by HA for each instance, plus a stable per-install
 * `deviceId` reused across re-registrations so HA keeps recognising us as the same device.
 *
 * The registration blob holds the webhook id and (when encryption is negotiated) the shared secret,
 * so it's stored AES-GCM-encrypted under an AndroidKeyStore key — same pattern as `TokenVault`,
 * separate key alias so the two concerns can be rotated independently.
 */
@SingleIn(AppScope::class)
@Inject
class MobileAppStore(private val context: Context) {

  private val Context.store by preferencesDataStore(name = "terrazzo_mobile_app")
  private val json = Json { ignoreUnknownKeys = true }

  /**
   * Returns the deviceId for this instance, generating and persisting a fresh UUID on first call.
   * Stable across re-registration so HA doesn't accrue duplicate mobile_app entries.
   *
   * Stored plaintext: the deviceId is an identifier, not a credential — the webhook id and secret
   * are what need protecting.
   */
  suspend fun getOrCreateDeviceId(instanceId: String): String {
    val prefs = context.store.data.first()
    prefs[deviceIdKey(instanceId)]?.let {
      return it
    }
    val fresh = UUID.randomUUID().toString().replace("-", "")
    context.store.edit { it[deviceIdKey(instanceId)] = fresh }
    return fresh
  }

  suspend fun put(instanceId: String, registration: MobileAppRegistration) {
    val plaintext =
      json
        .encodeToString(MobileAppRegistration.serializer(), registration)
        .toByteArray(Charsets.UTF_8)
    val (iv, ciphertext) = encrypt(plaintext)
    context.store.edit { prefs ->
      prefs[deviceIdKey(instanceId)] = registration.deviceId
      prefs[blobKey(instanceId)] = ciphertext.base64()
      prefs[ivKey(instanceId)] = iv.base64()
    }
  }

  suspend fun get(instanceId: String): MobileAppRegistration? {
    val prefs = context.store.data.first()
    val ciphertext = prefs[blobKey(instanceId)]?.fromBase64() ?: return null
    val iv = prefs[ivKey(instanceId)]?.fromBase64() ?: return null
    val bytes = decrypt(iv, ciphertext)
    return json.decodeFromString(MobileAppRegistration.serializer(), bytes.toString(Charsets.UTF_8))
  }

  suspend fun clear(instanceId: String) {
    context.store.edit { prefs ->
      prefs.remove(deviceIdKey(instanceId))
      prefs.remove(blobKey(instanceId))
      prefs.remove(ivKey(instanceId))
    }
  }

  private fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
    val ciphertext = cipher.doFinal(plaintext)
    return cipher.iv to ciphertext
  }

  private fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
    return cipher.doFinal(ciphertext)
  }

  private fun getOrCreateKey(): SecretKey {
    val keystore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    keystore.getKey(KEY_ALIAS, null)?.let {
      return it as SecretKey
    }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
    generator.init(
      KeyGenParameterSpec.Builder(
          KEY_ALIAS,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    )
    return generator.generateKey()
  }

  private companion object {
    const val KEYSTORE = "AndroidKeyStore"
    const val KEY_ALIAS = "terrazzo-mobile-app-v1"
    const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun deviceIdKey(instanceId: String) = stringPreferencesKey("mobile.$instanceId.device_id")

    fun blobKey(instanceId: String) = stringPreferencesKey("mobile.$instanceId.blob")

    fun ivKey(instanceId: String) = stringPreferencesKey("mobile.$instanceId.iv")

    fun ByteArray.base64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
  }
}
