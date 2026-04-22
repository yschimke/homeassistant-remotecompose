package ee.schimke.terrazzo.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64
import kotlinx.coroutines.flow.first

/**
 * Persists the HA refresh token as AES-GCM ciphertext in DataStore.
 * The symmetric key never leaves the AndroidKeyStore — on most modern
 * devices that's hardware-backed (TEE / StrongBox).
 *
 * Access token: short-lived, kept in memory only.
 * Refresh token: long-lived, encrypted here, used to mint fresh
 * access tokens via [HaAuthService].
 *
 * One vault per HA instance (keyed by `instanceId`), so a user with
 * multiple HA installs gets independent credential slots.
 */
class TokenVault(private val context: Context) {

    private val Context.store by preferencesDataStore(name = "terrazzo_token_vault")

    suspend fun put(instanceId: String, refreshToken: String) {
        val (iv, ciphertext) = encrypt(refreshToken.toByteArray(Charsets.UTF_8))
        context.store.edit { prefs ->
            prefs[cipherKey(instanceId)] = ciphertext.base64()
            prefs[ivKey(instanceId)] = iv.base64()
        }
    }

    suspend fun get(instanceId: String): String? {
        val prefs = context.store.data.first()
        val ciphertext = prefs[cipherKey(instanceId)]?.fromBase64() ?: return null
        val iv = prefs[ivKey(instanceId)]?.fromBase64() ?: return null
        return decrypt(iv, ciphertext).toString(Charsets.UTF_8)
    }

    suspend fun clear(instanceId: String) {
        context.store.edit { prefs ->
            prefs.remove(cipherKey(instanceId))
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
        keystore.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "terrazzo-token-vault-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        fun cipherKey(instanceId: String) = stringPreferencesKey("refresh.$instanceId")
        fun ivKey(instanceId: String) = stringPreferencesKey("refresh.$instanceId.iv")

        fun ByteArray.base64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
        fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
    }
}
