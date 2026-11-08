package ee.schimke.terrazzo.desktop

import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Ordinary classes deliberately do not include credentials in a generated toString().
@Serializable internal class DesktopSavedSignIn(val server: String, val refreshToken: String)

internal interface DesktopCredentialStore {
  fun read(): DesktopSavedSignIn?

  fun write(signIn: DesktopSavedSignIn)

  fun clear()
}

/**
 * Never fall back to preferences or a plaintext file when the OS credential store is unavailable.
 */
internal class KeyringDesktopCredentialStore : DesktopCredentialStore {
  private val service = "ee.schimke.terrazzo.desktop.homeassistant"
  private val account = "homeassistant"

  override fun read(): DesktopSavedSignIn? =
    Keyring.create().use { keyring ->
      // Java Keyring reports missing items as PasswordAccessException on macOS and Windows.
      // A locked/unreadable item also returns to sign-in, without deleting or overwriting it.
      val value =
        try {
          keyring.getPassword(service, account)
        } catch (_: PasswordAccessException) {
          null
        }
      value?.let { Json.decodeFromString<DesktopSavedSignIn>(it) }
    }

  override fun write(signIn: DesktopSavedSignIn) {
    Keyring.create().use { it.setPassword(service, account, Json.encodeToString(signIn)) }
  }

  override fun clear() {
    Keyring.create().use { it.deletePassword(service, account) }
  }
}
