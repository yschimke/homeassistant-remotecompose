package ee.schimke.terrazzo.restore

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ee.schimke.terrazzo.core.auth.RestoreKeyEscrow
import ee.schimke.terrazzo.core.auth.TokenVault
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Zero-tap sign-in restoration (issue #1595): the token vault's ciphertext comes back with the
 * app's backup, its data key comes back from the restore-key escrow, and every instance is signed
 * in again with no taps.
 *
 * A new phone is reproduced by deleting what never leaves a device, the Keystore key and the
 * wrapped data key in `noBackupFilesDir`, and keeping what is backed up: the vault in `filesDir`
 * and the escrow (in memory here; in the app, Credential Manager's restore credential).
 */
@RunWith(AndroidJUnit4::class)
class RestoreKeyEscrowTest {

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val escrow = InMemoryEscrow()
  private val tokenVault = TokenVault(context, escrow)

  @After
  fun tearDown(): Unit = runBlocking {
    tokenVault.clear(HOME)
    tokenVault.clear(CABIN)
  }

  @Test
  fun restoredPhoneRecoversEveryInstanceFromTheEscrow(): Unit = runBlocking {
    tokenVault.put(HOME, "home-refresh")
    tokenVault.put(CABIN, "cabin-refresh")
    tokenVault.putClientId(CABIN, "http://cabin.invalid:8123/")
    assertNotNull("Signing in escrows the vault key", escrow.key)

    moveToNewPhone()

    // A fresh instance, as the restored app's first launch has.
    val restored = TokenVault(context, escrow)
    assertEquals("home-refresh", restored.get(HOME))
    assertEquals("cabin-refresh", restored.get(CABIN))
    assertEquals("http://cabin.invalid:8123/", restored.clientId(CABIN))
    assertEquals(1, escrow.recoveries)

    // The recovered key is wrapped under this phone's Keystore; later reads don't ask again.
    assertEquals("home-refresh", TokenVault(context, escrow).get(HOME))
    assertEquals(1, escrow.recoveries)
  }

  @Test
  fun nothingEscrowedSignsInAgain(): Unit = runBlocking {
    tokenVault.put(HOME, "home-refresh")
    // An uninstall deletes the app's restore credential.
    escrow.key = null

    moveToNewPhone()

    val restored = TokenVault(context, escrow)
    assertNull(restored.get(HOME))
    restored.put(HOME, "home-again")
    assertEquals("home-again", restored.get(HOME))
  }

  @Test
  fun aKeyThatDoesNotOpenTheVaultSignsInAgain(): Unit = runBlocking {
    tokenVault.put(HOME, "home-refresh")
    escrow.key = ByteArray(32) { 7 }

    moveToNewPhone()

    assertNull(TokenVault(context, escrow).get(HOME))
  }

  @Test
  fun escrowUnavailableKeepsTheTokensForALaterRead(): Unit = runBlocking {
    tokenVault.put(HOME, "home-refresh")
    moveToNewPhone()

    escrow.unavailable = true
    assertNull(TokenVault(context, escrow).get(HOME))

    escrow.unavailable = false
    assertEquals("home-refresh", TokenVault(context, escrow).get(HOME))
  }

  @Test
  fun aFailedEscrowIsRetriedOnTheNextSignedInRead(): Unit = runBlocking {
    escrow.failStores = true
    tokenVault.put(HOME, "home-refresh")
    assertNull("Credential Manager was unavailable at sign-in", escrow.key)

    escrow.failStores = false
    // A later launch, signed in without a new sign-in or a rotated token.
    assertEquals("home-refresh", TokenVault(context, escrow).get(HOME))
    assertNotNull("The signed-in read escrowed the key", escrow.key)

    moveToNewPhone()
    assertEquals("home-refresh", TokenVault(context, escrow).get(HOME))
  }

  @Test
  fun signingOutOfEverythingClearsTheEscrow(): Unit = runBlocking {
    tokenVault.put(HOME, "home-refresh")
    tokenVault.put(CABIN, "cabin-refresh")

    tokenVault.clear(HOME)
    assertNotNull("Still signed in to the cabin", escrow.key)
    tokenVault.clear(CABIN)
    assertNull("A signed-out user isn't signed back in by a restore", escrow.key)
  }

  private fun moveToNewPhone() {
    KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS)
    File(context.noBackupFilesDir, DATA_KEY_FILE).delete()
    File(context.noBackupFilesDir, ESCROWED_FILE).delete()
  }

  private class InMemoryEscrow : RestoreKeyEscrow {
    var key: ByteArray? = null
    var unavailable = false
    var failStores = false
    var recoveries = 0

    override suspend fun store(key: ByteArray): Boolean {
      if (failStores) return false
      this.key = key.copyOf()
      return true
    }

    override suspend fun recover(): RestoreKeyEscrow.Recovery {
      recoveries++
      if (unavailable) return RestoreKeyEscrow.Recovery.Unavailable
      return key?.let { RestoreKeyEscrow.Recovery.Found(it.copyOf()) }
        ?: RestoreKeyEscrow.Recovery.None
    }

    override suspend fun clear() {
      key = null
    }
  }

  private companion object {
    const val HOME = "http://home.invalid:8123"
    const val CABIN = "http://cabin.invalid:8123"

    // TokenVault's private names; renaming one makes moveToNewPhone keep the key, and these fail.
    const val KEY_ALIAS = "terrazzo-token-vault-v1"
    const val DATA_KEY_FILE = "terrazzo_vault_dek.json"
    const val ESCROWED_FILE = "terrazzo_vault_dek.escrowed"
  }
}
