package ee.schimke.terrazzo.core.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import ee.schimke.terrazzo.core.di.AppScope
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persists the HA refresh token as AES-GCM ciphertext in an [AtomicFile] under
 * [Context.getNoBackupFilesDir]. The symmetric key never leaves the AndroidKeyStore — on most
 * modern devices that's hardware-backed (TEE / StrongBox).
 *
 * Stored in `noBackupFilesDir` rather than `preferencesDataStore` so:
 * - No `DataStoreInMemoryCache` or persistent coroutine scope stays pinned in RAM between reads.
 * - Device-bound `AndroidKeyStore` ciphertext is never backed up or restored to another device
 *   without its key.
 *
 * Access token: short-lived, kept in memory only. Refresh token: long-lived, encrypted here, used
 * to mint fresh access tokens via [HaAuthService].
 *
 * One vault per HA instance (keyed by `instanceId`), so a user with multiple HA installs gets
 * independent credential slots.
 */
@SingleIn(AppScope::class)
@Inject
class TokenVault(private val context: Context) {

  private val mutex = Mutex()
  private val json = Json { ignoreUnknownKeys = true }

  private val vaultFile: File
    get() = File(context.applicationContext.noBackupFilesDir, VAULT_FILE_NAME)

  private val legacyDataStoreFile: File
    get() = File(context.applicationContext.filesDir, "datastore/$LEGACY_STORE_NAME.preferences_pb")

  suspend fun put(instanceId: String, refreshToken: String) {
    val (iv, ciphertext) = encrypt(refreshToken.toByteArray(Charsets.UTF_8))
    updateVault { entries ->
      val current = entries[instanceId] ?: VaultEntry()
      entries[instanceId] = current.copy(ciphertext = ciphertext.base64(), iv = iv.base64())
    }
  }

  suspend fun get(instanceId: String): String? {
    val entry = readVault().entries[instanceId] ?: return null
    val ciphertext = entry.ciphertext?.fromBase64() ?: return null
    val iv = entry.iv?.fromBase64() ?: return null
    return decrypt(iv, ciphertext).toString(Charsets.UTF_8)
  }

  suspend fun clear(instanceId: String) {
    updateVault { entries -> entries.remove(instanceId) }
  }

  /**
   * Records the OAuth `client_id` [instanceId]'s refresh token was issued to, when it isn't the
   * app's own ([HaAuthService.CLIENT_ID]): a password sign-in ([HaAuthService.passwordLogin]) signs
   * in as the server's own frontend. Home Assistant only refreshes a token for the client it was
   * issued to. Not a secret, so kept in the clear.
   */
  suspend fun putClientId(instanceId: String, clientId: String) {
    updateVault { entries ->
      val current = entries[instanceId] ?: VaultEntry()
      entries[instanceId] = current.copy(clientId = clientId)
    }
  }

  /** The `client_id` to refresh [instanceId]'s token with: [putClientId]'s, else the app's own. */
  suspend fun clientId(instanceId: String): String =
    readVault().entries[instanceId]?.clientId ?: HaAuthService.CLIENT_ID

  private suspend fun readVault(): VaultData =
    mutex.withLock { withContext(Dispatchers.IO) { readVaultLocked() } }

  private suspend fun updateVault(transform: (MutableMap<String, VaultEntry>) -> Unit) {
    mutex.withLock {
      withContext(Dispatchers.IO) {
        val current = readVaultLocked()
        val updated = current.entries.toMutableMap()
        transform(updated)
        writeVaultLocked(VaultData(updated))
      }
    }
  }

  private suspend fun readVaultLocked(): VaultData {
    val file = vaultFile
    if (file.exists()) {
      return runCatching {
          val text = AtomicFile(file).readFully().toString(Charsets.UTF_8)
          json.decodeFromString(VaultData.serializer(), text)
        }
        .getOrDefault(VaultData())
    }
    val legacy = legacyDataStoreFile
    if (!legacy.exists()) return VaultData()
    return migrateLegacyLocked(legacy).also { migrated ->
      if (migrated.entries.isNotEmpty()) {
        writeVaultLocked(migrated)
      }
      legacy.delete()
    }
  }

  private fun writeVaultLocked(data: VaultData) {
    val file = vaultFile
    file.parentFile?.mkdirs()
    val atomic = AtomicFile(file)
    val stream = atomic.startWrite()
    try {
      stream.write(json.encodeToString(VaultData.serializer(), data).toByteArray(Charsets.UTF_8))
      atomic.finishWrite(stream)
    } catch (t: Throwable) {
      atomic.failWrite(stream)
      throw t
    }
  }

  private suspend fun migrateLegacyLocked(legacyFile: File): VaultData {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    return try {
      val store = PreferenceDataStoreFactory.create(scope = scope) { legacyFile }
      val prefs = store.data.first().asMap()
      val entries = mutableMapOf<String, VaultEntry>()
      for ((key, value) in prefs) {
        val str = value as? String ?: continue
        when {
          key.name.startsWith("refresh.") && key.name.endsWith(".iv") -> {
            val id = key.name.removePrefix("refresh.").removeSuffix(".iv")
            entries[id] = (entries[id] ?: VaultEntry()).copy(iv = str)
          }
          key.name.startsWith("refresh.") -> {
            val id = key.name.removePrefix("refresh.")
            entries[id] = (entries[id] ?: VaultEntry()).copy(ciphertext = str)
          }
          key.name.startsWith("client.") -> {
            val id = key.name.removePrefix("client.")
            entries[id] = (entries[id] ?: VaultEntry()).copy(clientId = str)
          }
        }
      }
      VaultData(entries)
    } finally {
      scope.cancel()
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
        .build(),
    )
    return generator.generateKey()
  }

  @Serializable
  private data class VaultData(val entries: Map<String, VaultEntry> = emptyMap())

  @Serializable
  private data class VaultEntry(
    val ciphertext: String? = null,
    val iv: String? = null,
    val clientId: String? = null,
  )

  private companion object {
    const val KEYSTORE = "AndroidKeyStore"
    const val KEY_ALIAS = "terrazzo-token-vault-v1"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val VAULT_FILE_NAME = "terrazzo_token_vault.json"
    const val LEGACY_STORE_NAME = "terrazzo_token_vault"

    fun ByteArray.base64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
  }
}
