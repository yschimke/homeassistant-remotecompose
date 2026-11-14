package ee.schimke.terrazzo.restore

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ee.schimke.terrazzo.TerrazzoGraphProvider
import ee.schimke.terrazzo.core.mobileapp.MobileAppRegistration
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A value encrypted under an AndroidKeyStore key whose key is gone reads as absent and is cleared.
 * It does not throw.
 *
 * This is what a restore from backup or a device transfer looks like to the stores: the ciphertext
 * comes back without its key. The backup rules leave both stores out (`BackupRestoreTest` in
 * `:app-container-tests` covers that end to end), but a backup made before those rules still
 * carries them, and so does a key the OS invalidates. Deleting the key here reproduces the loss
 * without a backup round trip.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreKeyLossTest {

  private val graph =
    (ApplicationProvider.getApplicationContext<Context>() as TerrazzoGraphProvider).graph

  @After
  fun tearDown(): Unit = runBlocking {
    graph.tokenVault.clear(INSTANCE)
    graph.mobileAppStore.clear(INSTANCE)
  }

  @Test
  fun refreshTokenWithoutItsKeyReadsAsSignedOut(): Unit = runBlocking {
    graph.tokenVault.put(INSTANCE, "refresh-before")
    assertEquals("refresh-before", graph.tokenVault.get(INSTANCE))

    deleteKeystoreKey(TOKEN_VAULT_KEY_ALIAS)

    assertNull(graph.tokenVault.get(INSTANCE))
    // The unreadable slot was cleared, so the next read is an ordinary miss.
    assertNull(graph.tokenVault.get(INSTANCE))
    // A fresh sign-in stores and reads back under the new key.
    graph.tokenVault.put(INSTANCE, "refresh-after")
    assertEquals("refresh-after", graph.tokenVault.get(INSTANCE))
  }

  @Test
  fun mobileAppRegistrationWithoutItsKeyReadsAsUnregistered(): Unit = runBlocking {
    val registration =
      MobileAppRegistration(deviceId = "device-1", deviceName = "Test", webhookId = "hook-1")
    graph.mobileAppStore.put(INSTANCE, registration)
    assertEquals("hook-1", graph.mobileAppStore.get(INSTANCE)?.webhookId)

    deleteKeystoreKey(MOBILE_APP_KEY_ALIAS)

    assertNull(graph.mobileAppStore.get(INSTANCE))
    assertNull(graph.mobileAppStore.get(INSTANCE))
    // The device id survives, so re-registering reuses it and HA sees the same device.
    assertEquals("device-1", graph.mobileAppStore.getOrCreateDeviceId(INSTANCE))
  }

  private fun deleteKeystoreKey(alias: String) {
    KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
  }

  private companion object {
    const val INSTANCE = "http://key-loss.invalid:8123"

    // The stores' private aliases. If one is renamed this test fails: deleting the old alias no
    // longer loses the key, so the read after it still returns the value.
    const val TOKEN_VAULT_KEY_ALIAS = "terrazzo-token-vault-v1"
    const val MOBILE_APP_KEY_ALIAS = "terrazzo-mobile-app-v1"
  }
}
