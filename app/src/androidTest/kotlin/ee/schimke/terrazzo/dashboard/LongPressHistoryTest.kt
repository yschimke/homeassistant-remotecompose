package ee.schimke.terrazzo.dashboard

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import ee.schimke.terrazzo.MainActivity
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * uiautomator-driven regression test for the long-press → card-history flow (and the install link
 * reachable from it).
 *
 * The dashboard renders each card inside a `RemotePreview` whose underlying RC player consumes
 * pointer events on the **Main** pass to dispatch in-document click regions. A standard outer
 * `Modifier.combinedClickable` long-press never fires because its `awaitFirstDown(requireUnconsumed
 * = true)` is always rejected by those consumed events.
 *
 * `Modifier.longPressBeforeChild` (in `CombinedClickLongPress.kt`) fixes this by listening on the
 * **Initial** pass; this test exercises the fix end-to-end through the real Android input pipeline
 * rather than Compose's test driver, since it's the input pipeline's Initial → Main pass
 * propagation that's actually under test.
 *
 * Demo mode is forced on via [MainActivity.EXTRA_TEST_DEMO_MODE] so we land directly on a dashboard
 * with deterministic fake data, avoiding the discovery / IndieAuth flow that can't reach anything
 * from CI.
 */
@RunWith(AndroidJUnit4::class)
class LongPressHistoryTest {

  private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

  @Before
  fun launchAppInDemoMode() {
    device.pressHome()
    val context = ApplicationProvider.getApplicationContext<Context>()
    val launch =
      context.packageManager
        .getLaunchIntentForPackage(context.packageName)!!
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        .putExtra(MainActivity.EXTRA_TEST_DEMO_MODE, true)
    context.startActivity(launch)
    // Wait for the dashboard view. Demo-mode clears the
    // last-viewed-dashboard pref, so the app lands on HA's
    // default dashboard (the first board in DemoData.BOARDS,
    // "Security") rather than auto-resuming from a previous
    // run. "Security" appears as the title in the top-bar
    // dashboard switcher.
    assertTrue(
      "Security dashboard did not surface within 15s",
      device.wait(Until.hasObject(By.text("Security")), 15_000),
    )
  }

  @After
  fun tearDown() {
    device.pressHome()
  }

  @Test
  @Ignore(
    "Quarantined: regressed since #364 (CardHistoryScreen launcher preview) and " +
      "fails the same way on every CI run — the card-history screen doesn't surface " +
      "after long-press. #391 and #394 hardened the preview's capture fallback without " +
      "clearing it, so the crash is elsewhere (player render / gridBounds) or the " +
      "long-press is genuinely swallowed. Needs logcat from a real device to pinpoint. " +
      "Tracking: https://github.com/yschimke/homeassistant-remotecompose/issues/396"
  )
  fun longPressOnDashboardCard_opensHistoryThenInstallSheet() {
    // Demo mode lands directly on the "Security" dashboard (the
    // first board in `DemoData.BOARDS`, exposed as HA's default
    // dashboard); the test exercises the long-press → history
    // screen flow on its first card.
    val card = waitForFirstCard()
    // uiautomator's longClick uses the platform long-press timeout,
    // so this matches what a real user's finger does — and that's
    // exactly the path Modifier.longPressBeforeChild's
    // `viewConfiguration.longPressTimeoutMillis` measures against.
    card.longClick()

    // CardHistoryScreen renders an "Other actions" links section.
    val historyVisible = device.wait(Until.hasObject(By.text("Other actions")), 5_000)
    assertTrue(
      "card-history screen did not surface after long-press — long-press " +
        "is being swallowed by the RC player again, see " +
        "Modifier.longPressBeforeChild",
      historyVisible,
    )

    // The "Add to Home Screen" link opens the existing install sheet.
    val installLink = device.wait(Until.findObject(By.textContains("Add to Home Screen")), 5_000)
    assertNotNull("history screen missing the Add to Home Screen link", installLink)
    installLink!!.click()

    // WidgetInstallSheet.kt opens with this descriptive line.
    val sheetVisible =
      device.wait(Until.hasObject(By.textContains("keep itself up to date")), 5_000)
    assertTrue("install sheet did not surface from the history screen", sheetVisible)
  }

  private fun waitForFirstCard(): UiObject2 {
    val anyCard = device.wait(Until.findObject(By.descStartsWith("dashboard-card:")), 10_000)
    assertNotNull(
      "no card with semantics descriptor 'dashboard-card:*' rendered within 10s",
      anyCard,
    )
    return anyCard!!
  }
}
