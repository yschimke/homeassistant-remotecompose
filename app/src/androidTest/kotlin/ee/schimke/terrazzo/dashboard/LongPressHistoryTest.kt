package ee.schimke.terrazzo.dashboard

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import ee.schimke.terrazzo.MainActivity
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
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
    // Sample outside the main thread so a blocked first composition leaves actionable stacks
    // in the CI logcat artifact. Stop sampling as soon as startup succeeds or fails.
    val startupSampler = Executors.newSingleThreadScheduledExecutor()
    startupSampler.scheduleAtFixedRate(
      {
        val stack = Looper.getMainLooper().thread.stackTrace.joinToString("\n")
        Log.i("DemoStartup", "Main thread while awaiting dashboard:\n$stack")
      },
      2,
      2,
      TimeUnit.SECONDS,
    )
    try {
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
      assertEquals(
        "Demo startup must stay in the app instead of opening a LAN permission dialog",
        context.packageName,
        device.currentPackageName,
      )
    } finally {
      startupSampler.shutdownNow()
    }
  }

  @After
  fun tearDown() {
    device.pressHome()
  }

  @Test
  fun forkUsesTheDashboardPreviewInsteadOfTheLauncherGrid() {
    waitForFirstCard().longClick()
    val fork = device.wait(Until.findObject(By.text("Fork in card designer")), 10_000)
    assertNotNull(fork)
    fork!!.click()
    assertTrue(device.wait(Until.hasObject(By.text("Design or fix this card")), 10_000))
    assertTrue(device.wait(Until.hasObject(By.descContains("Current card preview")), 15_000))
    assertTrue(
      "Forking must not show launcher size controls",
      !device.hasObject(By.text("Try it on your home screen")),
    )
    assertTrue(device.wait(Until.hasObject(By.text("Design in UI Builder")), 10_000))
  }

  @Test
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

    // Long-press offers the card's actions first: fork, home screen, history.
    val forkAction = device.wait(Until.findObject(By.text("Fork in card designer")), 10_000)
    assertNotNull(
      "card actions did not surface after long-press — long-press is being swallowed by the " +
        "RC player again, see Modifier.longPressBeforeChild",
      forkAction,
    )
    device.findObject(By.text("History and details"))!!.click()

    // The details screen's top bar is the signal that it opened: everything below it scrolls, and
    // the launcher preview at the top is tall enough to push the widget button below the fold on
    // a phone-sized emulator, where uiautomator's By.text matches only what is on screen. Looking
    // for that button without scrolling misdiagnosed a correctly opened screen as a lost
    // long-press.
    val historyVisible = device.wait(Until.hasObject(By.desc("Back")), 10_000)
    assertTrue(
      "card-history screen did not surface after long-press — long-press " +
        "is being swallowed by the RC player again, see " +
        "Modifier.longPressBeforeChild",
      historyVisible,
    )

    assertTrue(
      "card details must render the native launcher preview before installation",
      device.wait(Until.hasObject(By.descContains(" preview,")), 15_000),
    )

    // The "Prepare a home screen widget" button opens the existing install sheet. The screen's
    // column is the tallest scrollable; the launcher grid inside it scrolls only sideways.
    val installLink =
      device.findObject(By.text("Prepare a home screen widget"))
        ?: device
          .findObjects(By.scrollable(true))
          .maxByOrNull { it.visibleBounds.height() }
          ?.scrollUntil(Direction.DOWN, Until.findObject(By.text("Prepare a home screen widget")))
    assertNotNull("history screen missing the Add to Home Screen link", installLink)
    installLink!!.click()

    // WidgetInstallSheet.kt opens with this descriptive line.
    val sheetVisible = device.wait(Until.hasObject(By.text("Make it yours")), 10_000)
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
