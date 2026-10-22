package ee.schimke.terrazzo.wear.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import ee.schimke.terrazzo.wearsync.proto.WearAccess
import ee.schimke.terrazzo.wearsync.proto.decodeProto
import ee.schimke.terrazzo.wearsync.proto.encodeProto
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The watch's own Home Assistant token ([WearAccess]), which the phone made for it: kept as AES-GCM
 * ciphertext in `noBackupFilesDir`, so it is never part of a backup, under a key that never leaves
 * the AndroidKeyStore. Forgotten on sign-out ([clear]), when the phone signs in to another
 * instance, and when Home Assistant refuses it.
 */
class WearAccessStore(
  context: Context,
  private val cipher: AccessCipher = KeystoreAccessCipher,
) {
  private val file = File(context.noBackupFilesDir, "terrazzo/wear-access.bin")

  /** The stored token, or null when there is none or it can't be decrypted (a reset keystore). */
  fun read(): WearAccess? =
    file
      .readBytesOrNull()
      ?.let { runCatching { cipher.decrypt(it) }.getOrNull() }
      ?.let { decodeProto<WearAccess>(it) }
      ?.takeIf { it.token.isNotEmpty() && it.baseUrl.isNotEmpty() }

  fun write(access: WearAccess) {
    file.parentFile?.mkdirs()
    file.atomicWriteBytes(cipher.encrypt(encodeProto(access)))
  }

  fun clear() {
    file.delete()
  }
}

/** Seals the stored token; the Keystore in the app ([KeystoreAccessCipher]), a fake in tests. */
interface AccessCipher {
  fun encrypt(plaintext: ByteArray): ByteArray

  fun decrypt(sealed: ByteArray): ByteArray
}

/** AES-256-GCM under a non-exportable AndroidKeyStore key; the 12-byte IV leads the ciphertext. */
object KeystoreAccessCipher : AccessCipher {
  private const val KEYSTORE = "AndroidKeyStore"
  private const val KEY_ALIAS = "terrazzo-wear-access-v1"
  private const val TRANSFORMATION = "AES/GCM/NoPadding"
  private const val IV_BYTES = 12

  override fun encrypt(plaintext: ByteArray): ByteArray {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key())
    return cipher.iv + cipher.doFinal(plaintext)
  }

  override fun decrypt(sealed: ByteArray): ByteArray {
    require(sealed.size > IV_BYTES) { "too short" }
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
    return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
  }

  private fun key(): SecretKey {
    val keystore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    (keystore.getKey(KEY_ALIAS, null) as? SecretKey)?.let {
      return it
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
}
