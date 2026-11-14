package ee.schimke.terrazzo.containers

import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Test
import org.testcontainers.DockerClientFactory

/**
 * What a user moving to a new phone sees, end to end, against a real Home Assistant.
 *
 * 1. Start Home Assistant in Docker (Testcontainers) and onboard an owner.
 * 2. Install the debug app and its test APK on the emulator. Sign in from the app
 *    (`BackupRestoreSteps.signInBeforeBackup`), reaching Home Assistant at `10.0.2.2`.
 * 3. Back the app up with the local backup transport, which reports end-to-end encryption, as a
 *    phone with a screen lock does.
 * 4. Uninstall the app, which deletes its AndroidKeyStore keys as a new phone would not have them.
 *    Reinstall it and restore the backup.
 * 5. On the "new phone" (`BackupRestoreSteps.signedOutAfterRestore`), the app still knows its home.
 *    The token vault and the mobile_app registration were not restored, nothing throws, and signing
 *    in again works.
 *
 * Needs Docker and one booted emulator. Run it with `./gradlew :app-container-tests:test
 * -PcontainerTests=true`.
 */
class BackupRestoreTest {

  private val adb = Adb()
  private val evidenceDir =
    File(System.getProperty("terrazzo.evidenceDir", "build/container-tests"))
  private val appApk = File(System.getProperty("terrazzo.appApk", "missing-app.apk"))
  private val testApk = File(System.getProperty("terrazzo.testApk", "missing-test.apk"))

  /** How the emulator reaches this machine; override for a device on another network. */
  private val hostAddressForDevice = System.getProperty("terrazzo.deviceHostAddress", "10.0.2.2")

  @Test
  fun restoredAppIsSignedOutCleanlyAndSignsInAgain() {
    assertTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is not available")
    assertTrue(adb.deviceReady(), "No booted Android device or emulator is connected")
    assertTrue(appApk.isFile, "App APK missing: $appApk")
    assertTrue(testApk.isFile, "Test APK missing: $testApk")
    evidenceDir.mkdirs()

    HomeAssistantContainer().use { ha ->
      ha.start()
      ha.onboard(USERNAME, PASSWORD)
      val deviceUrl = ha.deviceUrl(hostAddressForDevice)
      val previousTransport = currentBackupTransport()
      try {
        reinstall(appApk)
        adb.run("install", "-r", "-t", testApk.absolutePath)
        adb.shell("logcat -c")

        runStep("signInBeforeBackup", deviceUrl)
        backUp()
        // A new phone: no app, so no Keystore keys, then the app back with its backup.
        reinstall(appApk)
        restore()
        runStep("signedOutAfterRestore", deviceUrl)
      } finally {
        File(evidenceDir, "logcat.txt")
          .writeText(runCatching { adb.run("logcat", "-d") }.getOrDefault(""))
        File(evidenceDir, "home-assistant.log").writeText(ha.logs)
        previousTransport?.let { runCatching { adb.shell("bmgr transport $it") } }
      }
    }
  }

  private fun reinstall(apk: File) {
    // Uninstalling deletes the app's data and its AndroidKeyStore keys. The first time, there may
    // be nothing to uninstall.
    runCatching { adb.run("uninstall", APP_PACKAGE) }
    adb.run("install", "-t", apk.absolutePath)
  }

  /** Backs [APP_PACKAGE] up with the local transport, flagged as end-to-end encrypted. */
  private fun backUp() {
    // `disableIfNoEncryptionCapabilities` in the app's backup rules leaves every file out unless
    // the transport reports client-side encryption (`FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED`). The
    // local transport reports it for `is_encrypted`. Its `fake_encryption_flag` sets a different,
    // test-only flag: with only that, the backup is empty and the transport rejects it (PFTBT
    // error -1002).
    adb.shell("settings put secure backup_local_transport_parameters is_encrypted=true")
    adb.shell("bmgr enable true")
    adb.shell("bmgr transport $LOCAL_TRANSPORT")
    runCatching { adb.shell("bmgr wipe $LOCAL_TRANSPORT $APP_PACKAGE") }
    val output = adb.shell("bmgr backupnow $APP_PACKAGE")
    if (!output.contains("Package $APP_PACKAGE with result: Success")) {
      // "Transport rejected package" here means the backup was empty: the local transport declines
      // zero-size full backups, which is what the backup rules make of an unencrypted transport.
      fail("Backup of $APP_PACKAGE did not succeed:\n$output")
    }
  }

  /**
   * Restores the backup into the reinstalled app. The local transport's only restore set is token
   * 1. [runStep] then checks that the restore happened: without it the restored app would not know
   *    its instance.
   */
  private fun restore() {
    adb.shell("bmgr restore 1 $APP_PACKAGE")
  }

  /** The transport selected before the test, marked `*` in `bmgr list transports`. */
  private fun currentBackupTransport(): String? = runCatching {
    adb
      .shell("bmgr list transports")
      .lines()
      .firstOrNull { it.trimStart().startsWith("*") }
      ?.trim()
      ?.removePrefix("*")
      ?.trim()
  }
    .getOrNull()

  /** Runs one `BackupRestoreSteps` test on the device; fails unless it ran and passed. */
  private fun runStep(name: String, haUrl: String) {
    val runner =
      adb
        .shell("pm list instrumentation")
        .lines()
        .map { it.trim() }
        .firstOrNull { it.endsWith("(target=$APP_PACKAGE)") }
        ?.removePrefix("instrumentation:")
        ?.substringBefore(" ")
        ?: fail("No instrumentation targets $APP_PACKAGE; is the test APK installed?")
    val output =
      adb.shell(
        listOf(
            "am instrument -w -r",
            "-e class $STEPS_CLASS#$name",
            "-e terrazzo.restore.haUrl $haUrl",
            "-e terrazzo.restore.username $USERNAME",
            "-e terrazzo.restore.password $PASSWORD",
            runner,
          )
          .joinToString(" "),
        timeoutMinutes = 10,
      )
    File(evidenceDir, "$name.txt").writeText(output)
    // -r reports each test's status: 0 passed, -2 failed, -3 ignored, -4 assumption failed (the
    // step was skipped). Anything but a pass, or a run that never finished, fails the test.
    val codes =
      Regex("INSTRUMENTATION_STATUS_CODE: (-?\\d+)")
        .findAll(output)
        .map { it.groupValues[1] }
        .toList()
    val finished = output.contains("INSTRUMENTATION_CODE: -1")
    if (!finished || "0" !in codes || codes.any { it != "0" && it != "1" }) {
      fail("Step $name did not pass on the device:\n$output")
    }
  }

  private companion object {
    const val APP_PACKAGE = "ee.schimke.harc"
    const val STEPS_CLASS = "ee.schimke.terrazzo.restore.BackupRestoreSteps"
    const val LOCAL_TRANSPORT = "com.android.localtransport/.LocalTransport"
    const val USERNAME = "restore"
    const val PASSWORD = "restore-test-password"
  }
}
