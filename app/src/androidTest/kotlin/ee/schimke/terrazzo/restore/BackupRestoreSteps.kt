package ee.schimke.terrazzo.restore

import android.Manifest
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import ee.schimke.terrazzo.MainActivity
import ee.schimke.terrazzo.TerrazzoGraphProvider
import ee.schimke.terrazzo.core.auth.HaAuthService
import ee.schimke.terrazzo.core.mobileapp.MobileAppRegistration
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
   * holds no token it can't read, opens on sign-in with that home filled in and its dashboards
   * kept, and signs in again normally.
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
      "The token vault in noBackupFilesDir is never backed up",
      File(context.noBackupFilesDir, "terrazzo_token_vault.json").exists(),
    )
    assertFalse(
      "The mobile_app registration is left out of backups",
      datastoreFile("terrazzo_mobile_app").exists(),
    )
    assertNull(graph.tokenVault.get(baseUrl))
    assertNull(graph.mobileAppStore.get(baseUrl))

    opensOnSignInAgain(baseUrl)

    signIn(baseUrl)
  }

  /**
   * Launches the app as the user would after the restore. With no token, the cold start goes to
   * sign-in with [baseUrl] filled in and a line naming it, and keeps the restored cache rather than
   * signing out.
   */
  private fun opensOnSignInAgain(baseUrl: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    // The app asks for notifications on launch; granted, no dialog covers the screen.
    instrumentation.uiAutomation.grantRuntimePermission(
      context.packageName,
      Manifest.permission.POST_NOTIFICATIONS,
    )
    val device = UiDevice.getInstance(instrumentation)
    val host = baseUrl.removePrefix("http://").removePrefix("https://")
    ActivityScenario.launch(MainActivity::class.java).use {
      assertTrue(
        "The sign-in screen should say which Home Assistant to sign in to again",
        device.wait(Until.hasObject(By.textContains("but not your sign-in")), UI_TIMEOUT_MS),
      )
      assertTrue(
        "The sign-in notice should name $host",
        device.hasObject(By.textContains("dashboards from $host")),
      )
      assertTrue("The URL field should start with $baseUrl", device.hasObject(By.text(baseUrl)))
      assertEquals(
        "Going to sign-in keeps the restored instance and its cache",
        baseUrl,
        graph.offlineCache.lastInstance(),
      )
    }
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

  private companion object {
    const val UI_TIMEOUT_MS = 20_000L
  }
}
