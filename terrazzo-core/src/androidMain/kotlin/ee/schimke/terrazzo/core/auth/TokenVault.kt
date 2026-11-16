package ee.schimke.terrazzo.core.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import ee.schimke.terrazzo.core.di.AppScope
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
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
 * Persists each HA instance's refresh token, encrypted, so it survives a move to a new phone
 * without the backup alone being enough to read it (zero-tap sign-in restoration, issue #1595).
 *
 * Envelope encryption, with two files and two keys:
 * - The **vault** ([VAULT_FILE_NAME], in `filesDir`, so backed up and transferred) holds every
 *   instance's refresh token as AES-256-GCM ciphertext under a random 32-byte data key. Without
 *   that key the backed-up copy is unreadable.
 * - The **data key** reaches a device two ways. On this device it is wrapped by an AndroidKeyStore
 *   key ([KEY_ALIAS], hardware-backed on most phones) in `noBackupFilesDir` ([DATA_KEY_FILE_NAME]),
 *   which is never backed up. For a new phone it is escrowed in Credential Manager's Restore
 *   Credentials ([RestoreKeyEscrow]), which Play services backs up end-to-end encrypted, separately
 *   from the app's backup.
 *
 * On a restored phone the vault comes back but the wrapped data key doesn't. The first read
 * recovers the key from the escrow, wraps it under this device's Keystore and reads on, so the app
 * is signed in to every instance with no taps. With nothing escrowed (no Play services, or the app
 * was uninstalled, which deletes its restore credential) the unreadable tokens are dropped and the
 * user signs in again, with the restored instance and dashboards kept.
 *
 * Access token: short-lived, kept in memory only. Refresh token: long-lived, encrypted here, used
 * to mint fresh access tokens via [HaAuthService].
 *
 * One slot per HA instance (keyed by `instanceId`), so a user with multiple HA installs gets
 * independent credentials; all of them restore together under the one data key.
 */
@SingleIn(AppScope::class)
class TokenVault(private val context: Context, private val escrow: RestoreKeyEscrow) {

  @Inject constructor(context: Context) : this(context, CredentialManagerRestoreKeyEscrow(context))

  private val mutex = Mutex()
  private val json = Json { ignoreUnknownKeys = true }
  private val random = SecureRandom()

  private val vaultFile: File
    get() = File(context.applicationContext.filesDir, VAULT_FILE_NAME)

  private val dataKeyFile: File
    get() = File(context.applicationContext.noBackupFilesDir, DATA_KEY_FILE_NAME)

  /** The vault before the data key: tokens encrypted directly under the Keystore key. */
  private val legacyVaultFile: File
    get() = File(context.applicationContext.noBackupFilesDir, LEGACY_VAULT_FILE_NAME)

  private val legacyDataStoreFile: File
    get() = File(context.applicationContext.filesDir, "datastore/$LEGACY_STORE_NAME.preferences_pb")

  suspend fun put(instanceId: String, refreshToken: String): Unit = locked {
    val dataKey = dataKeyLocked(create = true)!!
    val (iv, ciphertext) = seal(dataKey.key, instanceId, refreshToken.toByteArray(Charsets.UTF_8))
    updateVaultLocked { entries ->
      val current = entries[instanceId] ?: VaultEntry()
      entries[instanceId] = current.copy(ciphertext = ciphertext.base64(), iv = iv.base64())
    }
    // Every sign-in escrows again: cheap, and it heals an escrow that failed before (Play services
    // updated since, say) or that a newer data key replaced.
    escrow.store(dataKey.raw)
  }

  /**
   * The refresh token stored for [instanceId], or null when there is none, so the caller signs in
   * again.
   *
   * On a restored phone this is where the escrowed data key is recovered (see the class doc). A
   * token that still can't be decrypted reads as null and its slot is cleared
   * ([isUnreadableCiphertext]).
   */
  suspend fun get(instanceId: String): String? = locked {
    val entry = readVaultLocked().entries[instanceId] ?: return@locked null
    val ciphertext = entry.ciphertext ?: return@locked null
    val iv = entry.iv ?: return@locked null
    val dataKey = dataKeyLocked(create = false) ?: return@locked null
    try {
      open(dataKey.key, instanceId, iv.fromBase64(), ciphertext.fromBase64())
        .toString(Charsets.UTF_8)
    } catch (e: Exception) {
      if (!e.isUnreadableCiphertext()) throw e
      Log.w(TAG, "Stored refresh token can't be decrypted on this install; signing in again", e)
      clearLocked(instanceId)
      null
    }
  }

  suspend fun clear(instanceId: String): Unit = locked { clearLocked(instanceId) }

  /**
   * Records the OAuth `client_id` [instanceId]'s refresh token was issued to, when it isn't the
   * app's own ([HaAuthService.CLIENT_ID]): a password sign-in ([HaAuthService.passwordLogin]) signs
   * in as the server's own frontend. Home Assistant only refreshes a token for the client it was
   * issued to. Not a secret, so kept in the clear.
   */
  suspend fun putClientId(instanceId: String, clientId: String): Unit = locked {
    updateVaultLocked { entries ->
      val current = entries[instanceId] ?: VaultEntry()
      entries[instanceId] = current.copy(clientId = clientId)
    }
  }

  /** The `client_id` to refresh [instanceId]'s token with: [putClientId]'s, else the app's own. */
  suspend fun clientId(instanceId: String): String = locked {
    readVaultLocked().entries[instanceId]?.clientId ?: HaAuthService.CLIENT_ID
  }

  private suspend fun <T> locked(block: suspend () -> T): T = mutex.withLock {
    withContext(Dispatchers.IO) {
      migrateLegacyLocked()
      block()
    }
  }

  private suspend fun clearLocked(instanceId: String) {
    val remaining = updateVaultLocked { entries -> entries.remove(instanceId) }
    if (remaining.entries.values.none { it.ciphertext != null }) {
      // Signed out of everything: forget the data key, here and in the escrow, so a later restore
      // doesn't sign the user back in. The next sign-in makes a new one.
      dataKeyFile.delete()
      escrow.clear()
    }
  }

  /**
   * The vault's data key, or null when there is none and [create] is false.
   *
   * Unwrapped from [dataKeyFile] on this device. When that is missing or unreadable (a restored
   * phone, or a Keystore key the OS dropped) and the vault holds tokens, it is recovered from the
   * escrow and wrapped under this device's Keystore. Tokens no key can read are dropped; a new key
   * is made only when [create] asks for one.
   */
  private suspend fun dataKeyLocked(create: Boolean): DataKey? {
    unwrapStoredKeyLocked()?.let {
      return it
    }
    val vault = readVaultLocked()
    val sealed =
      vault.entries.entries.firstOrNull { it.value.ciphertext != null && it.value.iv != null }
    if (sealed != null) {
      when (val recovery = escrow.recover()) {
        is RestoreKeyEscrow.Recovery.Found -> {
          val recovered = DataKey(recovery.key)
          if (recovered.opens(sealed.key, sealed.value)) {
            Log.i(TAG, "Recovered the token vault key from the restore credential")
            wrapAndStoreLocked(recovered)
            return recovered
          }
          Log.w(TAG, "The escrowed key doesn't open this vault; signing in again")
        }
        RestoreKeyEscrow.Recovery.None ->
          Log.i(TAG, "No escrowed key for the restored token vault; signing in again")
        RestoreKeyEscrow.Recovery.Unavailable -> {
          // Maybe Play services isn't ready yet: keep the tokens for a later read, unless a
          // sign-in needs a key now.
          if (!create) return null
        }
      }
      writeVaultLocked(
        VaultData(vault.entries.mapValues { it.value.copy(ciphertext = null, iv = null) }),
      )
    }
    if (!create) return null
    return DataKey(ByteArray(DATA_KEY_BYTES).also(random::nextBytes)).also {
      wrapAndStoreLocked(it)
    }
  }

  private fun DataKey.opens(instanceId: String, entry: VaultEntry): Boolean =
    try {
      open(key, instanceId, entry.iv!!.fromBase64(), entry.ciphertext!!.fromBase64())
      true
    } catch (e: Exception) {
      if (!e.isUnreadableCiphertext()) throw e
      false
    }

  private fun unwrapStoredKeyLocked(): DataKey? {
    val file = dataKeyFile
    if (!file.exists()) return null
    return try {
      val wrapped =
        json.decodeFromString(
          WrappedKey.serializer(),
          AtomicFile(file).readFully().decodeToString(),
        )
      DataKey(keystoreDecrypt(wrapped.iv.fromBase64(), wrapped.ciphertext.fromBase64()))
    } catch (e: Exception) {
      if (!e.isUnreadableCiphertext()) throw e
      Log.w(TAG, "The token vault key can't be unwrapped on this install", e)
      file.delete()
      null
    }
  }

  private fun wrapAndStoreLocked(dataKey: DataKey) {
    val (iv, ciphertext) = keystoreEncrypt(dataKey.raw)
    writeAtomically(
      dataKeyFile,
      json.encodeToString(WrappedKey.serializer(), WrappedKey(iv.base64(), ciphertext.base64())),
    )
  }

  private fun readVaultLocked(): VaultData {
    val file = vaultFile
    if (!file.exists()) return VaultData()
    // An I/O error propagates: reading it as an empty vault would sign the user out, and the next
    // update would write that empty map over a file that may read fine on a retry. Only content
    // that can't be parsed (a SerializationException is an IllegalArgumentException) is
    // unrecoverable, and reads as empty.
    val text = AtomicFile(file).readFully().toString(Charsets.UTF_8)
    return try {
      json.decodeFromString(VaultData.serializer(), text)
    } catch (e: IllegalArgumentException) {
      Log.w(TAG, "Token vault can't be parsed; starting empty", e)
      VaultData()
    }
  }

  private fun updateVaultLocked(transform: (MutableMap<String, VaultEntry>) -> Unit): VaultData {
    val updated = readVaultLocked().entries.toMutableMap()
    transform(updated)
    return VaultData(updated).also(::writeVaultLocked)
  }

  private fun writeVaultLocked(data: VaultData) {
    writeAtomically(vaultFile, json.encodeToString(VaultData.serializer(), data))
  }

  private fun writeAtomically(file: File, text: String) {
    file.parentFile?.mkdirs()
    val atomic = AtomicFile(file)
    val stream = atomic.startWrite()
    try {
      stream.write(text.toByteArray(Charsets.UTF_8))
      atomic.finishWrite(stream)
    } catch (t: Throwable) {
      atomic.failWrite(stream)
      throw t
    }
  }

  /**
   * Moves tokens from an older layout into the vault, re-encrypted under the data key: the
   * Keystore-encrypted JSON file in `noBackupFilesDir`, or the DataStore before it. A token the
   * Keystore can no longer decrypt is dropped, so that instance signs in again.
   */
  private suspend fun migrateLegacyLocked() {
    val legacyJson = legacyVaultFile
    val legacyStore = legacyDataStoreFile
    if (!legacyJson.exists() && !legacyStore.exists()) return
    if (vaultFile.exists()) {
      // Already on the data key (a restored vault, say): the old files hold nothing newer.
      legacyJson.delete()
      legacyStore.delete()
      return
    }
    val legacy =
      when {
        legacyJson.exists() -> readLegacyJson(legacyJson)
        else -> readLegacyDataStore(legacyStore)
      }
    val tokens = mutableMapOf<String, VaultEntry>()
    var dataKey: DataKey? = null
    for ((instanceId, entry) in legacy.entries) {
      val token =
        try {
          if (entry.ciphertext != null && entry.iv != null) {
            keystoreDecrypt(entry.iv.fromBase64(), entry.ciphertext.fromBase64())
          } else {
            null
          }
        } catch (e: Exception) {
          if (!e.isUnreadableCiphertext()) throw e
          Log.w(TAG, "A legacy refresh token can't be decrypted; signing in again", e)
          null
        }
      tokens[instanceId] =
        if (token == null) {
          entry.copy(ciphertext = null, iv = null)
        } else {
          val key =
            dataKey
              ?: DataKey(ByteArray(DATA_KEY_BYTES).also(random::nextBytes)).also {
                wrapAndStoreLocked(it)
                dataKey = it
              }
          val (iv, ciphertext) = seal(key.key, instanceId, token)
          entry.copy(ciphertext = ciphertext.base64(), iv = iv.base64())
        }
    }
    writeVaultLocked(VaultData(tokens))
    legacyJson.delete()
    legacyStore.delete()
    dataKey?.let { escrow.store(it.raw) }
  }

  private fun readLegacyJson(file: File): VaultData =
    try {
      json.decodeFromString(VaultData.serializer(), AtomicFile(file).readFully().decodeToString())
    } catch (e: IllegalArgumentException) {
      Log.w(TAG, "Legacy token vault can't be parsed; starting empty", e)
      VaultData()
    }

  private suspend fun readLegacyDataStore(legacyFile: File): VaultData {
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

  /**
   * AES-GCM under the data key, bound to [instanceId] so a token can't be moved to another slot.
   */
  private fun seal(
    key: SecretKey,
    instanceId: String,
    plaintext: ByteArray,
  ): Pair<ByteArray, ByteArray> {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(
      Cipher.ENCRYPT_MODE,
      key,
      GCMParameterSpec(GCM_TAG_BITS, ByteArray(GCM_IV_BYTES).also(random::nextBytes)),
    )
    cipher.updateAAD(instanceId.toByteArray(Charsets.UTF_8))
    val ciphertext = cipher.doFinal(plaintext)
    return cipher.iv to ciphertext
  }

  private fun open(
    key: SecretKey,
    instanceId: String,
    iv: ByteArray,
    ciphertext: ByteArray,
  ): ByteArray {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
    cipher.updateAAD(instanceId.toByteArray(Charsets.UTF_8))
    return cipher.doFinal(ciphertext)
  }

  private fun keystoreEncrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKeystoreKey())
    val ciphertext = cipher.doFinal(plaintext)
    return cipher.iv to ciphertext
  }

  private fun keystoreDecrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, getOrCreateKeystoreKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
    return cipher.doFinal(ciphertext)
  }

  private fun getOrCreateKeystoreKey(): SecretKey {
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

  /** The vault's 32-byte AES data key. */
  private class DataKey(val raw: ByteArray) {
    val key: SecretKey = SecretKeySpec(raw, KeyProperties.KEY_ALGORITHM_AES)
  }

  @Serializable private data class WrappedKey(val iv: String, val ciphertext: String)

  @Serializable private data class VaultData(val entries: Map<String, VaultEntry> = emptyMap())

  @Serializable
  private data class VaultEntry(
    val ciphertext: String? = null,
    val iv: String? = null,
    val clientId: String? = null,
  )

  private companion object {
    const val TAG = "TokenVault"
    const val KEYSTORE = "AndroidKeyStore"
    const val KEY_ALIAS = "terrazzo-token-vault-v1"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val GCM_TAG_BITS = 128
    const val GCM_IV_BYTES = 12
    const val DATA_KEY_BYTES = 32

    /** In `filesDir`: backed up and transferred, readable only with the data key. */
    const val VAULT_FILE_NAME = "terrazzo_token_vault_backup.json"

    /** In `noBackupFilesDir`: the data key, wrapped by the Keystore key. */
    const val DATA_KEY_FILE_NAME = "terrazzo_vault_dek.json"

    /** In `noBackupFilesDir`: tokens under the Keystore key, before the data key existed. */
    const val LEGACY_VAULT_FILE_NAME = "terrazzo_token_vault.json"
    const val LEGACY_STORE_NAME = "terrazzo_token_vault"

    fun ByteArray.base64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
  }
}
