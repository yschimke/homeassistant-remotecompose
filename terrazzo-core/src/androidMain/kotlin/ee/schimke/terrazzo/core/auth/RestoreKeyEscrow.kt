package ee.schimke.terrazzo.core.auth

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CreateRestoreCredentialRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetRestoreCredentialOption
import androidx.credentials.RestoreCredential
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.restorecredential.E2eeUnavailableException
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Carries [TokenVault]'s data encryption key to a new phone, so a restored app is already signed in
 * (Google Play's zero-tap sign-in restoration, issue #1595).
 *
 * The vault's ciphertext travels in the app's backup; its key must not, or the backup alone would
 * hold the tokens. This escrows the key separately, in Credential Manager.
 */
interface RestoreKeyEscrow {

  /** Escrows [key] (32 bytes), replacing any key escrowed before. False when it can't. */
  suspend fun store(key: ByteArray): Boolean

  /** The escrowed key, read back on a restored phone. */
  suspend fun recover(): Recovery

  /** Forgets the escrowed key, so a signed-out user isn't signed back in by a later restore. */
  suspend fun clear()

  sealed interface Recovery {
    /** The key [store] escrowed. */
    class Found(val key: ByteArray) : Recovery

    /** Nothing is escrowed: none was stored, it was cleared, or the app was uninstalled. */
    data object None : Recovery

    /**
     * Credential Manager couldn't answer (no Play services, a transient error). Try again later.
     */
    data object Unavailable : Recovery
  }

  /** No escrow: the key stays on this device, and a restored phone signs in again. */
  object Disabled : RestoreKeyEscrow {
    override suspend fun store(key: ByteArray): Boolean = false

    override suspend fun recover(): Recovery = Recovery.None

    override suspend fun clear() {}
  }
}

/**
 * [RestoreKeyEscrow] on Credential Manager's Restore Credentials.
 *
 * A restore credential is a WebAuthn-shaped key pair that Play services keeps for this package and
 * signing certificate, backs up end-to-end encrypted, and returns to the same app on a new phone.
 * Nothing here talks to a server: the challenge is random and nobody checks the signature. What
 * matters is the WebAuthn `user.id`, an opaque buffer of up to 64 bytes (WebAuthn §5.4.3) that the
 * assertion returns verbatim as `userHandle`. The 32-byte key rides in it.
 *
 * Reading it back with [GetRestoreCredentialOption] is the "restore key retrieval" Play checks for.
 * Credential Manager deletes the credential when the app is uninstalled, so a reinstall on the same
 * phone signs in again rather than restoring silently.
 */
class CredentialManagerRestoreKeyEscrow(context: Context) : RestoreKeyEscrow {

  private val context = context.applicationContext
  private val credentialManager by lazy { CredentialManager.create(this.context) }
  private val json = Json { ignoreUnknownKeys = true }

  override suspend fun store(key: ByteArray): Boolean {
    require(key.size == KEY_BYTES) { "Expected a $KEY_BYTES-byte key" }
    val requestJson = createRequestJson(key)
    return try {
      try {
        credentialManager.createCredential(
          context,
          CreateRestoreCredentialRequest(requestJson, isCloudBackupEnabled = true),
        )
      } catch (_: E2eeUnavailableException) {
        // No screen lock, so no end-to-end encrypted cloud backup. Device-to-device transfer is
        // always end-to-end, so escrow for that alone.
        credentialManager.createCredential(
          context,
          CreateRestoreCredentialRequest(requestJson, isCloudBackupEnabled = false),
        )
      }
      true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // No Play services, or one too old for Restore Credentials. The vault still works on this
      // device; a restored phone signs in again.
      Log.w(TAG, "Can't escrow the token vault key; a restored phone will sign in again", e)
      false
    }
  }

  override suspend fun recover(): RestoreKeyEscrow.Recovery {
    val requestJson = buildJsonObject {
      put("challenge", randomChallenge())
      put("rpId", RP_ID)
      putJsonArray("allowCredentials") {}
      put("userVerification", "discouraged")
    }
      .toString()
    return try {
      val response =
        credentialManager.getCredential(
          context,
          GetCredentialRequest(listOf(GetRestoreCredentialOption(requestJson))),
        )
      val credential =
        response.credential as? RestoreCredential ?: return RestoreKeyEscrow.Recovery.None
      val userHandle =
        json
          .parseToJsonElement(credential.authenticationResponseJson)
          .jsonObject["response"]
          ?.jsonObject
          ?.get("userHandle")
          ?.jsonPrimitive
          ?.content
      val key = userHandle?.let(::fromBase64Url)
      if (key == null || key.size != KEY_BYTES) {
        Log.w(TAG, "The restore credential doesn't carry a token vault key")
        RestoreKeyEscrow.Recovery.None
      } else {
        RestoreKeyEscrow.Recovery.Found(key)
      }
    } catch (_: NoCredentialException) {
      RestoreKeyEscrow.Recovery.None
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Can't read the restore credential", e)
      RestoreKeyEscrow.Recovery.Unavailable
    }
  }

  override suspend fun clear() {
    try {
      credentialManager.clearCredentialState(
        ClearCredentialStateRequest(ClearCredentialStateRequest.TYPE_CLEAR_RESTORE_CREDENTIAL),
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Can't clear the restore credential", e)
    }
  }

  private fun createRequestJson(key: ByteArray): String = buildJsonObject {
    put("challenge", randomChallenge())
    putJsonObject("rp") {
      put("id", RP_ID)
      put("name", "Terrazzo")
    }
    putJsonObject("user") {
      put("id", toBase64Url(key))
      put("name", "terrazzo-token-vault")
      put("displayName", "Terrazzo sign-in")
    }
    putJsonArray("pubKeyCredParams") {
      for (alg in listOf(-7, -257)) {
        add(
          buildJsonObject {
            put("type", "public-key")
            put("alg", alg)
          },
        )
      }
    }
    put("timeout", 20_000)
    put("attestation", "none")
    put("excludeCredentials", buildJsonArray {})
    putJsonObject("authenticatorSelection") {
      put("residentKey", "required")
      put("requireResidentKey", JsonPrimitive(true))
      put("userVerification", "discouraged")
    }
  }
    .toString()

  private companion object {
    const val TAG = "RestoreKeyEscrow"
    const val KEY_BYTES = 32

    /**
     * The relying party the credential is created for. The restore key is bound to this app's
     * package and signing certificate, not checked against a server, so this only has to be stable:
     * changing it orphans keys already escrowed. It is the host of the app's OAuth redirect page
     * ([HaAuthService]).
     */
    const val RP_ID = "yschimke.github.io"

    const val BASE64_URL = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    fun randomChallenge(): String = toBase64Url(ByteArray(32).also(SecureRandom()::nextBytes))

    fun toBase64Url(bytes: ByteArray): String = Base64.encodeToString(bytes, BASE64_URL)

    fun fromBase64Url(text: String): ByteArray? =
      try {
        Base64.decode(text, BASE64_URL)
      } catch (_: IllegalArgumentException) {
        null
      }
  }
}
