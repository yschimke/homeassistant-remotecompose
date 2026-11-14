package ee.schimke.terrazzo.restore

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ee.schimke.terrazzo.TerrazzoGraphProvider
import ee.schimke.terrazzo.core.auth.HaAuthService
import ee.schimke.terrazzo.core.mobileapp.MobileAppRegistration
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The on-device halves of the backup and restore test (`BackupRestoreTest` in
 * `:app-container-tests`). The host runs them one at a time with `am instrument`, and does the
 * backup, uninstall, reinstall and restore in between with `adb`.
 *
 * Each step needs the Home Assistant the host started, passed as instrumentation arguments:
 * `terrazzo.restore.haUrl`, `terrazzo.restore.username` and `terrazzo.restore.password`. With no
 * URL each step is skipped, so a plain `connectedDebugAndroidTest` passes over them.
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreSteps {

  private val args = InstrumentationRegistry.getArguments()
  private val haUrl: String? = args.getString("terrazzo.restore.haUrl")
  private val username = args.getString("terrazzo.restore.username") ?: "admin"
  private val password = args.getString("terrazzo.restore.password") ?: "admin"

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val graph = (context as TerrazzoGraphProvider).graph

  /** Before the backup: sign in as the app would, and leave state in every store under test. */
  @Test
  fun signInBeforeBackup(): Unit = runBlocking {
    val baseUrl = requireHaUrl()
    signIn(baseUrl)
    graph.offlineCache.setLastInstance(baseUrl)
    graph.mobileAppStore.put(
      baseUrl,
      MobileAppRegistration(
        deviceId = "old-phone",
        deviceName = "Old phone",
        webhookId = "old-hook",
      ),
    )

    assertNotNull(graph.tokenVault.get(baseUrl))
    assertEquals("old-hook", graph.mobileAppStore.get(baseUrl)?.webhookId)
  }

  /**
   * After the reinstall and restore, which is what a new phone sees: the app still knows its home,
   * holds no token it can't read, and signs in again normally.
   */
  @Test
  fun signedOutAfterRestore(): Unit = runBlocking {
    val baseUrl = requireHaUrl()

    // If this fails, nothing was restored, so the rest would pass for the wrong reason.
    assertEquals(
      "The restored app should know the instance it used before the backup",
      baseUrl,
      graph.offlineCache.lastInstance(),
    )

    assertFalse(
      "The token vault is left out of backups",
      datastoreFile("terrazzo_token_vault").exists(),
    )
    assertFalse(
      "The mobile_app registration is left out of backups",
      datastoreFile("terrazzo_mobile_app").exists(),
    )
    assertNull(graph.tokenVault.get(baseUrl))
    assertNull(graph.mobileAppStore.get(baseUrl))

    signIn(baseUrl)
  }

  /** Signs in with a password, stores the token as the app does, and proves it refreshes. */
  private suspend fun signIn(baseUrl: String) {
    val tokens = graph.authService.passwordLogin(baseUrl, username, password)
    val refresh = checkNotNull(tokens.refreshToken) { "Home Assistant returned no refresh token" }
    graph.tokenVault.put(baseUrl, refresh)
    graph.tokenVault.putClientId(baseUrl, HaAuthService.passwordClientId(baseUrl))
    graph.preferencesStore.setDemoMode(false)

    val stored = checkNotNull(graph.tokenVault.get(baseUrl))
    val refreshed =
      graph.authService.refreshAccessToken(baseUrl, stored, graph.tokenVault.clientId(baseUrl))
    assertNotNull("The stored token refreshes", refreshed.accessToken)
  }

  private fun requireHaUrl(): String {
    assumeTrue("Run by BackupRestoreTest in :app-container-tests", haUrl != null)
    return checkNotNull(haUrl).trimEnd('/')
  }

  private fun datastoreFile(name: String) = File(context.filesDir, "datastore/$name.preferences_pb")
}
